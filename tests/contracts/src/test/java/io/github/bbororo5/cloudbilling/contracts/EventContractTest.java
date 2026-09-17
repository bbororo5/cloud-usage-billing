package io.github.bbororo5.cloudbilling.contracts;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class EventContractTest {
    @Test
    void approvedExampleIsValid() {
        assertThat(EventContractOracle.violations(example())).isEmpty();
    }

    @Test
    void zeroStorageAndNetworkAreValid() {
        ObjectNode event = example();
        record(event, 1).put("ConsumedQuantity", 0);
        record(event, 2).put("ConsumedQuantity", 0);
        assertThat(EventContractOracle.violations(event)).isEmpty();
    }

    @Test
    void occupancyAnchoredIntervalNeedNotStartOnMinuteBoundary() {
        ObjectNode event = example();
        event.put("time", "2026-08-12T00:01:35Z");
        for (int i = 0; i < 3; i++) {
            record(event, i).put("ChargePeriodStart", "2026-08-12T00:00:35Z");
            record(event, i).put("ChargePeriodEnd", "2026-08-12T00:01:35Z");
        }
        assertThat(EventContractOracle.violations(event)).isEmpty();
    }

    @Test
    void meterArrayOrderIsNotPartOfTheContract() {
        ObjectNode event = example();
        ArrayNode reordered = ContractFiles.JSON.createArrayNode();
        reordered.add(record(event, 2).deepCopy()).add(record(event, 0).deepCopy())
                .add(record(event, 1).deepCopy());
        event.set("data", reordered);
        assertThat(EventContractOracle.violations(event)).isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> missingRequiredFieldsFail() {
        Stream<DynamicTest> envelope = List.of("specversion", "id", "source", "type", "subject",
                "time", "datacontenttype", "dataschema", "data").stream()
                .map(field -> dynamicTest("missing envelope " + field, () -> {
                    ObjectNode event = example();
                    event.remove(field);
                    assertThat(EventContractOracle.violations(event)).contains("SCHEMA");
                }));
        Stream<DynamicTest> payload = List.of("ChargePeriodStart", "ChargePeriodEnd", "RegionId",
                "ResourceId", "ResourceType", "Meter", "ConsumedQuantity", "ConsumedUnit").stream()
                .map(field -> dynamicTest("missing record " + field, () -> {
                    ObjectNode event = example();
                    record(event, 0).remove(field);
                    assertThat(EventContractOracle.violations(event)).contains("SCHEMA");
                }));
        return Stream.concat(envelope, payload);
    }

    @TestFactory
    Stream<DynamicTest> schemaRejectsFocusedViolations() {
        return Stream.of(
                change("invalid UUID", e -> e.put("id", "not-a-uuid")),
                change("invalid time", e -> e.put("time", "not-a-time")),
                change("invalid source", e -> e.put("source", "vm-1")),
                change("unsupported version", e -> e.put("specversion", "2.0")),
                change("company in envelope", e -> e.put("BillingAccountId", "company-1")),
                change("company in data", e -> record(e, 0).put("BillingAccountId", "company-1")),
                change("price identifier", e -> record(e, 0).put("SkuId", "sku-1")),
                change("wrong unit", e -> record(e, 0).put("ConsumedUnit", "Byte")),
                change("negative quantity", e -> record(e, 2).put("ConsumedQuantity", -1)),
                change("fractional quantity", e -> record(e, 2).put("ConsumedQuantity", 0.5)),
                change("quantity string", e -> record(e, 2).put("ConsumedQuantity", "1")),
                change("missing meter", e -> ((ArrayNode) e.path("data")).remove(2)),
                change("extra meter", e -> ((ArrayNode) e.path("data")).add(record(e, 0).deepCopy())),
                change("duplicate meter", e -> ((ArrayNode) e.path("data")).set(2, record(e, 0).deepCopy()))
        ).map(change -> dynamicTest(change.name(), () -> {
            ObjectNode event = example();
            change.apply().accept(event);
            assertThat(EventContractOracle.violations(event)).contains("SCHEMA");
        }));
    }

    @TestFactory
    Stream<DynamicTest> durationBoundariesFailEvenWhenSchemaAllowsThem() {
        return Stream.of(0, 35, 59, 61).map(seconds -> dynamicTest(seconds + " seconds", () -> {
            ObjectNode event = example();
            String start = java.time.Instant.parse("2026-08-12T00:01:00Z").minusSeconds(seconds).toString();
            for (int i = 0; i < 3; i++) record(event, i).put("ChargePeriodStart", start);
            assertThat(EventContractOracle.violations(event)).contains("SIXTY_SECONDS").doesNotContain("SCHEMA");
        }));
    }

    @TestFactory
    Stream<DynamicTest> relationsFailEvenWhenSchemaAllowsThem() {
        return Stream.of(
                change("subject mismatch", e -> e.put("subject", "instances/another-vm")),
                change("event time mismatch", e -> e.put("time", "2026-08-12T00:02:00Z")),
                change("resource mismatch", e -> record(e, 1).put("ResourceId", "another-vm")),
                change("interval mismatch", e -> {
                    record(e, 1).put("ChargePeriodStart", "2026-08-12T00:01:00Z");
                    record(e, 1).put("ChargePeriodEnd", "2026-08-12T00:02:00Z");
                }),
                change("non UTC time", e -> e.put("time", "2026-08-12T09:01:00+09:00")),
                change("compute quantity mismatch", e -> record(e, 0).put("ConsumedQuantity", 59))
        ).map(change -> dynamicTest(change.name(), () -> {
            ObjectNode event = example();
            change.apply().accept(event);
            assertThat(EventContractOracle.violations(event)).isNotEmpty().doesNotContain("SCHEMA");
        }));
    }

    private static ObjectNode example() {
        return (ObjectNode) ContractFiles.read("v1/examples/instance-usage-event.json");
    }

    private static ObjectNode record(ObjectNode event, int index) {
        return (ObjectNode) event.path("data").get(index);
    }

    private static Change change(String name, Consumer<ObjectNode> apply) {
        return new Change(name, apply);
    }

    private record Change(String name, Consumer<ObjectNode> apply) { }
}
