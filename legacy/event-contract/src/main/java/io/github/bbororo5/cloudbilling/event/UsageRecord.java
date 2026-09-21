package io.github.bbororo5.cloudbilling.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record UsageRecord(
        @JsonProperty("ChargePeriodStart") OffsetDateTime chargePeriodStart,
        @JsonProperty("ChargePeriodEnd") OffsetDateTime chargePeriodEnd,
        @JsonProperty("RegionId") String regionId,
        @JsonProperty("ResourceId") String resourceId,
        @JsonProperty("ResourceType") String resourceType,
        @JsonProperty("Meter") String meter,
        @JsonProperty("ConsumedQuantity") long consumedQuantity,
        @JsonProperty("ConsumedUnit") String consumedUnit
) {
}
