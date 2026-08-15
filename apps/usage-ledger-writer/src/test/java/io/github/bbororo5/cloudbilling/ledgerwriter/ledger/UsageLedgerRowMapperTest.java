package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEvent;
import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLedgerRowMapperTest {

    private final UsageLedgerRowMapper mapper = new UsageLedgerRowMapper();

    @Test
    void expandsOneDeliveryIntoThreeRowsWithDeliveryIdentity() {
        byte[] payload = exampleBytes();
        InstanceUsageEvent event = new InstanceUsageEventParser().parse(payload);
        ConsumerRecord<String, byte[]> delivery = new ConsumerRecord<>(
                "usage-events.v1",
                4,
                99L,
                "tenant-001:i-000123",
                payload
        );

        List<UsageLedgerRow> rows = mapper.map(event, delivery);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(UsageLedgerRow::serviceCategory)
                .containsExactlyInAnyOrder("Compute", "Storage", "Networking");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.kafkaTopic()).isEqualTo("usage-events.v1");
            assertThat(row.kafkaPartition()).isEqualTo(4);
            assertThat(row.kafkaOffset()).isEqualTo(99L);
            assertThat(row.payloadHash())
                    .isEqualTo("ec9f479711f19a94f77790343e7a154139ad2cc00d55f60967b3e8debc67ee6c");
        });
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
