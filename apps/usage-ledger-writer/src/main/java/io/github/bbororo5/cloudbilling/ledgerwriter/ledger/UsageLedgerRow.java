package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record UsageLedgerRow(
        String billingAccountId,
        URI eventSource,
        UUID eventId,
        Instant eventTime,
        String eventSubject,
        Instant chargePeriodStart,
        Instant chargePeriodEnd,
        String regionId,
        String resourceId,
        String resourceType,
        String serviceCategory,
        String serviceName,
        String skuId,
        String skuMeter,
        long consumedQuantity,
        String consumedUnit,
        String payloadHash,
        String kafkaTopic,
        int kafkaPartition,
        long kafkaOffset
) {
}
