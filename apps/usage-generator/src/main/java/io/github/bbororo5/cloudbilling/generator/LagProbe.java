package io.github.bbororo5.cloudbilling.generator;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only operator probe: never joins the ingestion group or changes its offsets. */
public final class LagProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected config path and lag-age threshold seconds");
        Properties properties=new Properties();
        try(var in=Files.newInputStream(Path.of(args[0]))) { properties.load(in); }
        properties.put("default.api.timeout.ms","10000");
        properties.put("request.timeout.ms","10000");
        long threshold=Long.parseLong(args[1]);
        if(threshold<0) throw new IllegalArgumentException("Negative threshold");
        Map<TopicPartition,Long> pending=new HashMap<>();
        long lag=0;
        try(var admin=Admin.create(properties)) {
            var topic=admin.describeTopics(List.of("usage-events.v1")).allTopicNames().get(10,TimeUnit.SECONDS).get("usage-events.v1");
            var latestRequest=new HashMap<TopicPartition,OffsetSpec>();
            var earliestRequest=new HashMap<TopicPartition,OffsetSpec>();
            topic.partitions().forEach(p->{var tp=new TopicPartition("usage-events.v1",p.partition());
                latestRequest.put(tp,OffsetSpec.latest()); earliestRequest.put(tp,OffsetSpec.earliest());});
            var latest=admin.listOffsets(latestRequest).all().get(10,TimeUnit.SECONDS);
            var earliest=admin.listOffsets(earliestRequest).all().get(10,TimeUnit.SECONDS);
            var committed=admin.listConsumerGroupOffsets("usage-clickhouse-v1").partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
            for(var entry:latest.entrySet()) {
                var tp=entry.getKey(); var position=committed.get(tp);
                if(position==null || position.offset()<earliest.get(tp).offset() || position.offset()>entry.getValue().offset())
                    throw new IllegalStateException("Missing or expired consumer position: "+tp);
                long count=entry.getValue().offset()-position.offset(); lag+=count;
                if(count>0) pending.put(tp,position.offset());
            }
        }
        long oldestAge=0;
        if(!pending.isEmpty()) {
            properties.put("enable.auto.commit","false");
            properties.put("auto.offset.reset","none");
            properties.put("key.deserializer",ByteArrayDeserializer.class.getName());
            properties.put("value.deserializer",ByteArrayDeserializer.class.getName());
            try(var consumer=new KafkaConsumer<byte[],byte[]>(properties)) {
                consumer.assign(pending.keySet()); pending.forEach(consumer::seek);
                Set<TopicPartition> remaining=new HashSet<>(pending.keySet());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(!remaining.isEmpty() && System.nanoTime()<deadline) {
                    for(var record:consumer.poll(Duration.ofMillis(500))) {
                        var tp=new TopicPartition(record.topic(),record.partition());
                        if(remaining.remove(tp)) {
                            if(record.timestamp()<0) throw new IllegalStateException("Missing Kafka record timestamp");
                            oldestAge=Math.max(oldestAge,Math.max(0,(System.currentTimeMillis()-record.timestamp())/1000));
                        }
                    }
                }
                if(!remaining.isEmpty()) throw new IllegalStateException("Could not inspect oldest pending records");
            }
        }
        System.out.println("lag="+lag+" oldestPendingSeconds="+oldestAge);
        if(lag>0 && oldestAge>=threshold) { System.err.println("ALERT ingestion retention risk"); System.exit(2); }
    }
}
