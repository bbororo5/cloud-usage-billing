package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.ReceiptService;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.ReceiptStore;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;

/** All methods, including close, run on the owning consumer thread. */
public final class OccupancyConsumer implements AutoCloseable {
    private final Consumer<byte[],byte[]> consumer;
    private final ReceiptService receipts;
    private final EventDecoder decoder;
    public OccupancyConsumer(Consumer<byte[],byte[]> consumer,ReceiptService receipts,EventDecoder decoder) {
        this.consumer=consumer;this.receipts=receipts;this.decoder=decoder;
    }
    public int pollOnce(Duration timeout) {
        var records=consumer.poll(timeout);
        var starts=new HashMap<TopicPartition,Long>();
        var durable=new HashMap<TopicPartition,OffsetAndMetadata>();
        for(var p:records.partitions()) starts.put(p,records.records(p).getFirst().offset());
        try {
            for(var record:records) {
                var headers=new ArrayList<ReceiptStore.Header>();
                record.headers().forEach(h->headers.add(new ReceiptStore.Header(h.key(),h.value())));
                var raw=new ReceiptStore.Record(record.topic(),record.partition(),record.offset(),record.key(),headers,record.value());
                receipts.receive(decoder.decode(raw));
                durable.put(new TopicPartition(record.topic(),record.partition()),new OffsetAndMetadata(record.offset()+1));
            }
        } catch(RuntimeException failure) {
            try { if(!durable.isEmpty()) consumer.commitSync(durable); }
            catch(RuntimeException commitFailure) {failure.addSuppressed(commitFailure);}
            rewind(starts,failure);throw failure;
        }
        try {if(!durable.isEmpty()) consumer.commitSync(durable);}
        catch(RuntimeException failure) {rewind(starts,failure);throw failure;}
        return records.count();
    }
    private void rewind(Map<TopicPartition,Long> starts,RuntimeException failure) {
        for(var entry:starts.entrySet()) try {
            if(consumer.assignment().contains(entry.getKey())) consumer.seek(entry.getKey(),entry.getValue());
        } catch(RuntimeException seekFailure) {failure.addSuppressed(seekFailure);}
    }
    @Override public void close() {consumer.close(Duration.ofSeconds(10));}
}
