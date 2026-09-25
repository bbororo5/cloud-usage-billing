package io.github.bbororo5.cloudbilling.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.ReceiptStore;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.QueryService;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresSnapshotStore;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache.LocalSnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;
import static org.junit.jupiter.api.Assertions.*;

@Tag("kafka")
class KafkaRecoveryTest extends PersistenceTest {
    @TempDir Path temporary;
    static KafkaContainer broker;
    @BeforeAll static void startKafka() {
        broker=new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));broker.start();
    }
    @AfterAll static void stopKafka() {if(broker!=null)broker.stop();}
    Properties properties(String group) {
        var p=new Properties();p.put("bootstrap.servers",broker.getBootstrapServers());p.put("group.id",group);
        p.put("enable.auto.commit","false");p.put("auto.offset.reset","earliest");p.put("max.poll.records","100");
        p.put("key.deserializer",ByteArrayDeserializer.class.getName());p.put("value.deserializer",ByteArrayDeserializer.class.getName());
        return p;
    }
    @Test @Timeout(90) void durableUncommittedReceiptReplaysOnNewConsumer() throws Exception {
        String topic="occupancy-"+UUID.randomUUID();String group=topic+"-group";
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get();
        }
        var mapper=new ObjectMapper();
        var n=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(Path.of(System.getProperty("contracts.dir"),"examples/occupancy/initialized.json")));
        n.put("source",source);
        try(var producer=new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",broker.getBootstrapServers(),"acks","all"),new ByteArraySerializer(),new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic,source.getBytes(),mapper.writeValueAsBytes(n))).get();
        }
        var decoder=new EventDecoder();
        try(var first=new KafkaConsumer<byte[],byte[]>(properties(group))) {
            first.subscribe(List.of(topic));
            ConsumerRecords<byte[],byte[]> polled=ConsumerRecords.empty();
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(polled.isEmpty()&&System.nanoTime()<deadline)polled=first.poll(Duration.ofMillis(100));
            assertEquals(1,polled.count());
            for(var r:polled) receipt.receive(decoder.decode(new ReceiptStore.Record(r.topic(),r.partition(),r.offset(),r.key(),List.of(),r.value())));
            // Crash boundary: durable inbox commit succeeded; Kafka offset never committed.
        }
        try(var second=new KafkaConsumer<byte[],byte[]>(properties(group)); var runner=new OccupancyConsumer(second,receipt,decoder)) {
            second.subscribe(List.of(topic));
            int count=0;long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(count==0&&System.nanoTime()<deadline)count+=runner.pollOnce(Duration.ofMillis(100));
            assertEquals(1,count);
            assertEquals(1,second.committed(Set.of(new TopicPartition(topic,0))).get(new TopicPartition(topic,0)).offset());
        }
        apply.applyNext(source);
        assertEquals(1,number("select count(*) from billing.occupancy_receipt where topic='"+topic+"'"));
        assertEquals(1,number("select version from billing.occupancy_stream where source='"+source+"'"));
    }
    @Test @Timeout(120) void realWorkerCrashAndRestartDoesNotBlockOtherVm() throws Exception {
        String topic="acceptance-"+UUID.randomUUID();
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get();
        }
        Path config=temporary.resolve("kafka.properties");
        Files.writeString(config,"bootstrap.servers="+broker.getBootstrapServers()+"\ngroup.id="+topic+"\nsession.timeout.ms=6000\nheartbeat.interval.ms=2000\n");
        var json=new ObjectMapper();
        java.util.function.BiFunction<String,String,byte[]> wire=(vm,kind)->{
            try {
                var n=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(Path.of(System.getProperty("contracts.dir"),"examples/occupancy/"+kind+".json")));
                n.put("source",vm);
                if(kind.equals("started"))((com.fasterxml.jackson.databind.node.ObjectNode)n.get("data")).put("billingAccountId","x");
                if(vm.endsWith("-b")&&kind.equals("confirmed"))((com.fasterxml.jackson.databind.node.ObjectNode)n.get("data")).put("sequence",2);
                return json.writeValueAsBytes(n);
            }catch(Exception e){throw new RuntimeException(e);}
        };
        String other=source+"-b";
        try(var producer=new KafkaProducer<byte[],byte[]>(Map.of("bootstrap.servers",broker.getBootstrapServers(),"acks","all"),new ByteArraySerializer(),new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic,source.getBytes(),wire.apply(source,"initialized"))).get();
            producer.send(new ProducerRecord<>(topic,source.getBytes(),wire.apply(source,"ended"))).get();
            producer.send(new ProducerRecord<>(topic,other.getBytes(),wire.apply(other,"initialized"))).get();
            producer.send(new ProducerRecord<>(topic,other.getBytes(),wire.apply(other,"confirmed"))).get();
            Process first=worker(config,topic,"first");
            try {
                await(()->number("select count(*) from billing.occupancy_stream where source='"+other+"' and last_applied_sequence=2")==1,first);
                assertEquals(1,number("select last_applied_sequence from billing.occupancy_stream where source='"+source+"'"));
                var reader=new QueryService(tx,new PostgresSnapshotStore(tx),new LocalSnapshotCache(10));
                assertInstanceOf(HistoryReader.Confirmed.class,reader.lookup(new HistoryReader.Query(other,t,t.plusSeconds(120))));
                assertInstanceOf(HistoryReader.NotReady.class,reader.lookup(new HistoryReader.Query(source,t,t.plusSeconds(120))));
            }finally{first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));}
            producer.send(new ProducerRecord<>(topic,source.getBytes(),wire.apply(source,"started"))).get();
            producer.send(new ProducerRecord<>(topic,source.getBytes(),wire.apply(source,"confirmed"))).get();
            producer.send(new ProducerRecord<>(topic,source.getBytes(),wire.apply(source,"initialized"))).get();
            Process second=worker(config,topic,"second");
            try {
                await(()->number("select count(*) from billing.occupancy_stream where source='"+source+"' and last_applied_sequence=4")==1,second);
                assertEquals(1,number("select count(*) from billing.occupancy_interval where source='"+source+"'"));
                assertEquals(4,number("select version from billing.occupancy_stream where source='"+source+"'"));
                var reader=new QueryService(tx,new PostgresSnapshotStore(tx),new LocalSnapshotCache(10));
                assertEquals(1,((HistoryReader.Confirmed)reader.lookup(new HistoryReader.Query(source,t,t.plusSeconds(120)))).snapshot().slices().size());
            }finally{second.destroy();if(!second.waitFor(35,TimeUnit.SECONDS)){second.destroyForcibly();fail("Worker did not stop gracefully");}}
        }
    }
    private Process worker(Path config,String topic,String log) throws Exception {
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",System.getProperty("worker.jar"));
        command.environment().put("OCCUPANCY_DB_URL",System.getenv("OCCUPANCY_TEST_URL"));
        command.environment().put("OCCUPANCY_DB_PASSWORD","local-dev-only");
        command.environment().put("OCCUPANCY_KAFKA_CONFIG",config.toString());
        command.environment().put("OCCUPANCY_TOPIC",topic);
        return command.redirectErrorStream(true).redirectOutput(temporary.resolve(log+".log").toFile()).start();
    }
    private void await(BooleanSupplier condition,Process process) {
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(!condition.getAsBoolean()&&process.isAlive()&&System.nanoTime()<deadline)LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        if(!condition.getAsBoolean()) {
            try(var files=Files.list(temporary)) {files.filter(p->p.toString().endsWith(".log")).forEach(p->{try{System.err.println(Files.readString(p));}catch(Exception ignored){}});}
            catch(Exception ignored) { }
            fail("Worker did not reach persisted state");
        }
    }
}
