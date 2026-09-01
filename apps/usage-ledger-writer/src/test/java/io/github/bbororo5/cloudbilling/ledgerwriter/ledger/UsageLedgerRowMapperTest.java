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
                "urn:cloud-usage:meter:generator-01",
                payload
        );

        List<UsageLedgerRow> rows = mapper.map(event, delivery);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(UsageLedgerRow::meter)
                .containsExactlyInAnyOrder("Compute Usage", "Block Volume Usage", "Data Transfer");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.kafkaTopic()).isEqualTo("usage-events.v1");
            assertThat(row.kafkaPartition()).isEqualTo(4);
            assertThat(row.kafkaOffset()).isEqualTo(99L);
            assertThat(row.payloadHash())
                    .isEqualTo("ad3def079c5e060675c66ad115c94f67ca45133a417205c839bbe3f3a99c1e57");
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
