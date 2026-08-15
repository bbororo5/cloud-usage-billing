package io.github.bbororo5.cloudbilling.event;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class UsageEventSemanticValidator {

    List<String> validate(InstanceUsageEvent event) {
        List<String> violations = new ArrayList<>();
        UsageRecord first = event.records().getFirst();

        if (!event.subject().equals("instances/" + first.resourceId())) {
            violations.add("SUBJECT_RESOURCE_MISMATCH");
        }
        if (!event.time().toInstant().equals(first.chargePeriodEnd().toInstant())) {
            violations.add("EVENT_TIME_MISMATCH");
        }
        if (!event.time().getOffset().equals(ZoneOffset.UTC)) {
            violations.add("EVENT_TIME_NOT_UTC");
        }

        long durationMillis = Duration.between(
                first.chargePeriodStart().toInstant(),
                first.chargePeriodEnd().toInstant()
        ).toMillis();
        if (durationMillis < 1_000 || durationMillis > 60_000 || durationMillis % 1_000 != 0) {
            violations.add("INVALID_CHARGE_PERIOD");
        }

        for (UsageRecord record : event.records()) {
            if (!sameScope(first, record)) {
                violations.add("INCONSISTENT_RECORD_SCOPE");
                break;
            }
            if (!record.chargePeriodStart().getOffset().equals(ZoneOffset.UTC)
                    || !record.chargePeriodEnd().getOffset().equals(ZoneOffset.UTC)) {
                violations.add("CHARGE_PERIOD_NOT_UTC");
                break;
            }
        }

        event.records().stream()
                .filter(record -> record.serviceCategory().equals("Compute"))
                .findFirst()
                .filter(record -> record.consumedQuantity() != durationMillis / 1_000)
                .ifPresent(record -> violations.add("COMPUTE_QUANTITY_PERIOD_MISMATCH"));

        return violations;
    }

    private boolean sameScope(UsageRecord first, UsageRecord other) {
        return first.billingAccountId().equals(other.billingAccountId())
                && first.chargePeriodStart().toInstant().equals(other.chargePeriodStart().toInstant())
                && first.chargePeriodEnd().toInstant().equals(other.chargePeriodEnd().toInstant())
                && first.regionId().equals(other.regionId())
                && first.resourceId().equals(other.resourceId())
                && first.resourceType().equals(other.resourceType());
    }
}
