package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEvent;
import io.github.bbororo5.cloudbilling.event.UsageRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
class UsageLedgerRowMapper {

    List<UsageLedgerRow> map(
            InstanceUsageEvent event,
            ConsumerRecord<String, byte[]> delivery
    ) {
        String payloadHash = sha256(delivery.value());
        return event.records().stream()
                .map(record -> mapRecord(event, record, delivery, payloadHash))
                .toList();
    }

    private UsageLedgerRow mapRecord(
            InstanceUsageEvent event,
            UsageRecord record,
            ConsumerRecord<String, byte[]> delivery,
            String payloadHash
    ) {
        return new UsageLedgerRow(
                record.billingAccountId(),
                event.source(),
                event.id(),
                event.time().toInstant(),
                event.subject(),
                record.chargePeriodStart().toInstant(),
                record.chargePeriodEnd().toInstant(),
                record.regionId(),
                record.resourceId(),
                record.resourceType(),
                record.serviceCategory(),
                record.serviceName(),
                record.skuId(),
                record.skuMeter(),
                record.consumedQuantity(),
                record.consumedUnit(),
                payloadHash,
                delivery.topic(),
                delivery.partition(),
                delivery.offset()
        );
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
