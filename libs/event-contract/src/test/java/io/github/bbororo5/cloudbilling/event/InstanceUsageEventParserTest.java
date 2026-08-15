package io.github.bbororo5.cloudbilling.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceUsageEventParserTest {

    private final InstanceUsageEventParser parser = new InstanceUsageEventParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesTheContractExample() {
        InstanceUsageEvent event = parser.parse(exampleBytes());

        assertThat(event.source().toString())
                .isEqualTo("urn:cloud-usage:meter:generator-01");
        assertThat(event.records()).hasSize(3);
        assertThat(event.records()).extracting(UsageRecord::serviceCategory)
                .containsExactlyInAnyOrder("Compute", "Storage", "Networking");
    }

    @Test
    void rejectsMalformedJsonAtEnvelopeStage() {
        assertThatThrownBy(() -> parser.parse("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        UsageEventValidationException.class,
                        exception -> assertThat(exception.stage())
                                .isEqualTo(ValidationStage.ENVELOPE)
                );
    }

    @Test
    void rejectsMissingRequiredFieldAtSchemaStage() throws Exception {
        JsonNode root = exampleNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("source");

        assertStage(root, ValidationStage.SCHEMA);
    }

    @Test
    void rejectsDifferentBillingAccountsAtSemanticStage() throws Exception {
        JsonNode root = exampleNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("data").get(1))
                .put("BillingAccountId", "tenant-002");

        assertStage(root, ValidationStage.SEMANTIC);
    }

    @Test
    void rejectsEventTimeDifferentFromChargePeriodEnd() throws Exception {
        JsonNode root = exampleNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root)
                .put("time", "2026-08-12T00:02:00Z");

        assertStage(root, ValidationStage.SEMANTIC);
    }

    @Test
    void rejectsPeriodLongerThanSixtySeconds() throws Exception {
        JsonNode root = exampleNode();
        for (JsonNode record : root.path("data")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) record)
                    .put("ChargePeriodStart", "2026-08-11T23:59:59Z");
        }

        assertStage(root, ValidationStage.SEMANTIC);
    }

    private void assertStage(JsonNode root, ValidationStage expectedStage) throws Exception {
        byte[] payload = objectMapper.writeValueAsBytes(root);
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOfSatisfying(
                        UsageEventValidationException.class,
                        exception -> assertThat(exception.stage()).isEqualTo(expectedStage)
                );
    }

    private JsonNode exampleNode() throws IOException {
        return objectMapper.readTree(exampleBytes());
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
