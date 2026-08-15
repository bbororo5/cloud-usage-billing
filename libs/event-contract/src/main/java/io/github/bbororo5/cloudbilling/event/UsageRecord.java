package io.github.bbororo5.cloudbilling.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record UsageRecord(
        @JsonProperty("BillingAccountId") String billingAccountId,
        @JsonProperty("ChargePeriodStart") OffsetDateTime chargePeriodStart,
        @JsonProperty("ChargePeriodEnd") OffsetDateTime chargePeriodEnd,
        @JsonProperty("RegionId") String regionId,
        @JsonProperty("ResourceId") String resourceId,
        @JsonProperty("ResourceType") String resourceType,
        @JsonProperty("ServiceCategory") String serviceCategory,
        @JsonProperty("ServiceName") String serviceName,
        @JsonProperty("SkuId") String skuId,
        @JsonProperty("SkuMeter") String skuMeter,
        @JsonProperty("ConsumedQuantity") long consumedQuantity,
        @JsonProperty("ConsumedUnit") String consumedUnit
) {
}
