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
import static org.junit.jupiter.api.Assertions.*;

@Tag("kafka")
class KafkaRecoveryTest extends PersistenceTest {
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
}
