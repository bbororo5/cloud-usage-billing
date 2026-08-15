package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UsageEventBatchListenerTest {

    private final UsageLedgerRepository repository = mock(UsageLedgerRepository.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final UsageEventBatchListener listener = new UsageEventBatchListener(
            new InstanceUsageEventParser(),
            new UsageLedgerRowMapper(),
            repository
    );

    @Test
    void acknowledgesOffsetsOnlyAfterAllRowsAreWritten() {
        listener.consume(List.of(delivery()), acknowledgment);

        var order = inOrder(repository, acknowledgment);
        order.verify(repository).append(anyList());
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void leavesOffsetsUncommittedWhenClickHouseWriteFails() {
        doThrow(new IllegalStateException("ClickHouse unavailable"))
                .when(repository).append(anyList());

        assertThatThrownBy(() -> listener.consume(List.of(delivery()), acknowledgment))
                .isInstanceOf(IllegalStateException.class);

        verify(acknowledgment, never()).acknowledge();
    }

    private ConsumerRecord<String, byte[]> delivery() {
        return new ConsumerRecord<>(
                "usage-events.v1",
                0,
                0L,
                "tenant-001:i-000123",
                exampleBytes()
        );
    }

    private byte[] exampleBytes() {
        try (InputStream input = getClass().getResourceAsStream(
                "/examples/instance-usage-event.json"
        )) {
            if (input == null) {
                throw new IllegalStateException("event example resource is missing");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read event example", exception);
        }
    }
}
