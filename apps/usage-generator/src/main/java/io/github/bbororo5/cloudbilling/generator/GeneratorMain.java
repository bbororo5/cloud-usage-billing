package io.github.bbororo5.cloudbilling.generator;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class GeneratorMain {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        if (args.length%2!=0) throw new IllegalArgumentException("Expected --name value pairs");
        for(int i=0;i<args.length;i+=2) {
            if(!Set.of("--config","--source","--resource","--from","--seconds","--storage-gib","--network-bytes","--pace-ms","--vms").contains(args[i])
                    || options.put(args[i],args[i+1])!=null) throw new IllegalArgumentException("Unknown/duplicate argument: "+args[i]);
        }
        for(String key:List.of("--config","--source","--resource","--from","--seconds"))
            if(!options.containsKey(key)) throw new IllegalArgumentException("Missing "+key);
        Instant start=Instant.parse(options.get("--from"));
        long seconds=Long.parseLong(options.get("--seconds"));
        long pace=Long.parseLong(options.getOrDefault("--pace-ms","60000"));
        int vms=Integer.parseInt(options.getOrDefault("--vms","1"));
        if(seconds<0 || pace<0 || vms<1 || vms>100_000 || start.plusSeconds(seconds).isAfter(Instant.now()))
            throw new IllegalArgumentException("Use a completed occupancy interval, nonnegative pace and 1..100000 VMs");
        var generators=new ArrayList<UsageGenerator>();
        for(int vm=0;vm<vms;vm++) {
            String suffix=vms==1?"":"-"+vm;
            generators.add(new UsageGenerator(options.get("--source")+suffix,options.get("--resource")+suffix,start,
                new BigInteger(options.getOrDefault("--storage-gib","100")),
                new BigInteger(options.getOrDefault("--network-bytes","1048576"))));
        }
        Properties properties=new Properties();
        try(var input=Files.newInputStream(Path.of(options.get("--config")))) { properties.load(input); }
        properties.put("acks","all");
        properties.put("enable.idempotence","true");
        properties.put("key.serializer",StringSerializer.class.getName());
        properties.put("value.serializer",StringSerializer.class.getName());
        properties.put("delivery.timeout.ms","120000");
        properties.put("request.timeout.ms","30000");
        properties.put("max.block.ms","10000");
        long acknowledged=0;
        try(var producer=new KafkaProducer<String,String>(properties)) {
            var publisher=new AckPublisher(producer,()->Thread.sleep(1000));
            for(long interval=0;interval<seconds/60;interval++) {
                if(interval>0 && pace>0) Thread.sleep(pace);
                for(var generator:generators) { publisher.publish(generator.event(interval)); acknowledged++; }
                System.out.println("ACKED="+acknowledged);
            }
        }
        System.out.println("COMPLETE acked="+acknowledged+" discardedTailSeconds="+seconds%60);
    }
}
