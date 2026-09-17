package io.github.bbororo5.cloudbilling.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Test-only oracle derived from docs/event-contract.md, not the legacy ingestion parser. */
final class EventContractOracle {
    private static final Schema SCHEMA = ContractFiles.schema(
            ContractFiles.read("v1/instance-usage-event.schema.json"));

    static List<String> violations(JsonNode event) {
        if (!ContractFiles.errors(SCHEMA, event).isEmpty()) {
            return List.of("SCHEMA");
        }
        List<String> errors = new ArrayList<>();
        JsonNode first = event.path("data").get(0);
        OffsetDateTime end = time(first, "ChargePeriodEnd");
        if (!event.path("subject").asText().equals("instances/" + first.path("ResourceId").asText())) {
            errors.add("SUBJECT_RESOURCE");
        }
        if (!time(event, "time").toInstant().equals(end.toInstant())) {
            errors.add("EVENT_TIME");
        }
        if (!time(event, "time").getOffset().equals(ZoneOffset.UTC)) {
            errors.add("UTC");
        }
        for (JsonNode record : event.path("data")) {
            OffsetDateTime start = time(record, "ChargePeriodStart");
            OffsetDateTime recordEnd = time(record, "ChargePeriodEnd");
            if (!Duration.between(start, recordEnd).equals(Duration.ofSeconds(60))) {
                errors.add("SIXTY_SECONDS");
            }
            if (!start.getOffset().equals(ZoneOffset.UTC) || !recordEnd.getOffset().equals(ZoneOffset.UTC)) {
                errors.add("UTC");
            }
            if (!start.toInstant().equals(time(first, "ChargePeriodStart").toInstant())
                    || !recordEnd.toInstant().equals(end.toInstant())
                    || !record.path("ResourceId").equals(first.path("ResourceId"))
                    || !record.path("RegionId").equals(first.path("RegionId"))) {
                errors.add("SAME_SCOPE");
            }
            if (record.path("Meter").asText().equals("Compute Usage")
                    && record.path("ConsumedQuantity").longValue() != 60) {
                errors.add("COMPUTE_SECONDS");
            }
        }
        return errors;
    }

    private static OffsetDateTime time(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText());
    }

    private EventContractOracle() { }
}
