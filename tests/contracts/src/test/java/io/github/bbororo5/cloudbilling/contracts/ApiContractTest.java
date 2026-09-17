package io.github.bbororo5.cloudbilling.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class ApiContractTest {
    private final JsonNode api = ContractFiles.read("openapi.yaml");

    @Test
    void userErrorsExposeOnlyCodeAndTraceId() {
        JsonNode error = api.at("/components/schemas/Error");
        List<String> properties = new ArrayList<>();
        error.path("properties").fieldNames().forEachRemaining(properties::add);
        assertThat(properties).containsExactlyInAnyOrder("code", "traceId");
        List<String> required = new ArrayList<>();
        error.path("required").forEach(field -> required.add(field.asText()));
        assertThat(required).containsExactlyInAnyOrder("code", "traceId");
        assertThat(error.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void costQueryDeclaresUnavailableResponseForCalculationDependencies() {
        assertThat(api.at("/paths/~1costs/get/responses/503/$ref").asText())
                .isEqualTo("#/components/responses/CostUnavailable");
        assertThat(api.at("/components/responses/CostUnavailable/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/Error");
    }

    @Test
    void referencesResolveLocallyWithoutNetwork() {
        checkReferences(api);
    }

    @Test
    void userOperationsRequireSessionExceptLoginAndDoNotAcceptTenantScope() {
        api.path("paths").fields().forEachRemaining(path -> {
            if (path.getKey().equals("/usage-events")) return; // explicitly retained legacy contract
            path.getValue().fields().forEachRemaining(operation -> {
                if (!List.of("get", "post", "put", "delete").contains(operation.getKey())) return;
                JsonNode definition = operation.getValue();
                if (!(path.getKey().equals("/session") && operation.getKey().equals("post"))) {
                    assertThat(definition.at("/security/0/UserSession").isArray())
                            .as(path.getKey() + " " + operation.getKey()).isTrue();
                }
                Stream.concat(elements(path.getValue().path("parameters")), elements(definition.path("parameters")))
                        .map(this::resolve).forEach(parameter -> assertThat(parameter.path("name").asText())
                                .isNotIn("tenantId", "billingAccountId"));
                definition.path("responses").fields().forEachRemaining(response -> {
                    if (Integer.parseInt(response.getKey()) < 400) return;
                    JsonNode schema = resolve(response.getValue()).at("/content/application~1problem+json/schema");
                    assertThat(schema.path("$ref").asText()).isEqualTo("#/components/schemas/Error");
                });
            });
        });
    }

    @Test
    void costResponseBindingUsesTheSchemaCoveredByFixtures() {
        assertThat(api.at("/paths/~1costs/get/responses/200/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/CostResponse");
    }

    @TestFactory
    Stream<DynamicTest> approvedResponseAndRequestFixtures() {
        return elements(ContractFiles.read("examples/user-api-cases.json")).map(fixture ->
                dynamicTest(fixture.path("name").asText(), () -> {
                    String name = fixture.path("schema").asText();
                    assertThat(api.at("/components/schemas/" + name).isMissingNode()).isFalse();
                    ObjectNode root = ContractFiles.JSON.createObjectNode();
                    root.put("$ref", "#/components/schemas/" + name);
                    root.set("components", api.path("components"));
                    var errors = ContractFiles.errors(ContractFiles.schema(root), fixture.path("value"));
                    assertThat(errors.isEmpty()).as("%s: %s", fixture.path("name"), errors)
                            .isEqualTo(fixture.path("valid").asBoolean());
                }));
    }

    @TestFactory
    Stream<DynamicTest> queryParameterBoundaries() {
        return Stream.of(
                new ParameterCase("cost lower limit", "/costs", "limit", "1", true),
                new ParameterCase("cost upper limit", "/costs", "limit", "100", true),
                new ParameterCase("cost below limit", "/costs", "limit", "0", false),
                new ParameterCase("cost above limit", "/costs", "limit", "101", false),
                new ParameterCase("usage upper limit", "/usage-records", "limit", "200", true),
                new ParameterCase("usage above limit", "/usage-records", "limit", "201", false),
                new ParameterCase("two dimensions", "/costs", "groupBy", "[\"day\",\"service\"]", true),
                new ParameterCase("three dimensions", "/costs", "groupBy", "[\"day\",\"service\",\"resource\"]", false),
                new ParameterCase("duplicate dimension", "/costs", "groupBy", "[\"day\",\"day\"]", false),
                new ParameterCase("unknown dimension", "/costs", "groupBy", "[\"tenant\"]", false),
                new ParameterCase("invalid date", "/costs", "from", "\"not-a-date\"", false)
        ).map(test -> dynamicTest(test.name(), () -> {
            JsonNode parameter = elements(api.path("paths").path(test.path()).path("get").path("parameters"))
                    .map(this::resolve).filter(p -> p.path("name").asText().equals(test.parameter()))
                    .findFirst().orElseThrow();
            Schema schema = ContractFiles.schema(parameter.path("schema"));
            assertThat(ContractFiles.errors(schema, ContractFiles.JSON.readTree(test.json())).isEmpty())
                    .isEqualTo(test.valid());
        }));
    }

    private JsonNode resolve(JsonNode node) {
        return node.has("$ref") ? api.at(node.path("$ref").asText().substring(1)) : node;
    }

    private void checkReferences(JsonNode node) {
        if (node.has("$ref")) {
            String reference = node.path("$ref").asText();
            if (reference.startsWith("#/")) {
                assertThat(api.at(reference.substring(1)).isMissingNode()).as(reference).isFalse();
            } else {
                assertThat(reference).isEqualTo("./v1/instance-usage-event.schema.json");
                assertThat(ContractFiles.read(reference).isObject()).isTrue();
            }
        }
        node.elements().forEachRemaining(this::checkReferences);
    }

    private static Stream<JsonNode> elements(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false);
    }

    private record ParameterCase(String name, String path, String parameter, String json, boolean valid) { }
}
