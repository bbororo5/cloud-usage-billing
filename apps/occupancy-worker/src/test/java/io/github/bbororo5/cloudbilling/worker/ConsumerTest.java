package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.ReceiptService;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class ConsumerTest {
  final TransactionRunner tx =
      new TransactionRunner() {
        public <T> T write(Supplier<T> w) {
          return w.get();
        }

        public <T> T read(Supplier<T> w) {
          return w.get();
        }
      };

  @Test
  void commitsOnlyStoredPrefixAndRewindsAfterStorageFailure() {
    var c = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST);
    var p = new TopicPartition("occupancy", 0);
    c.assign(List.of(p));
    c.updateBeginningOffsets(Map.of(p, 0L));
    c.seek(p, 0);
    c.addRecord(new ConsumerRecord<>(p.topic(), 0, 0, null, "bad".getBytes()));
    c.addRecord(new ConsumerRecord<>(p.topic(), 0, 5, null, "bad".getBytes()));
    var service =
        new ReceiptService(
            tx,
            input -> {
              if (input.record().offset() == 5) throw new IllegalStateException("DB unavailable");
            });
    var consumer = new OccupancyConsumer(c, service, new EventDecoder());
    assertThrows(IllegalStateException.class, () -> consumer.pollOnce(Duration.ZERO));
    assertEquals(1, c.committed(Set.of(p)).get(p).offset());
    assertEquals(0, c.position(p));
  }

  @Test
  void failedCommitRewindsInsteadOfSkippingDurableRecords() {
    var c =
        new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
          @Override
          public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            throw new org.apache.kafka.common.errors.TimeoutException("lost reply");
          }
        };
    var p = new TopicPartition("occupancy", 0);
    c.assign(List.of(p));
    c.updateBeginningOffsets(Map.of(p, 0L));
    c.seek(p, 0);
    c.addRecord(new ConsumerRecord<>(p.topic(), 0, 7, null, "bad".getBytes()));
    var consumer =
        new OccupancyConsumer(c, new ReceiptService(tx, input -> {}), new EventDecoder());
    assertThrows(RuntimeException.class, () -> consumer.pollOnce(Duration.ZERO));
    assertEquals(7, c.position(p));
  }
}
