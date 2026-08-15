package io.github.bbororo5.cloudbilling.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class InstanceUsageEventParser {

    private static final String SCHEMA_RESOURCE = "/instance-usage-event.schema.json";

    private final EventFormat cloudEventFormat;
    private final ObjectMapper objectMapper;
    private final Schema schema;
    private final UsageEventSemanticValidator semanticValidator;

    public InstanceUsageEventParser() {
        this.cloudEventFormat = Objects.requireNonNull(
                EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE),
                "CloudEvents JSON format is unavailable"
        );
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.schema = loadSchema();
        this.semanticValidator = new UsageEventSemanticValidator();
    }

    public InstanceUsageEvent parse(byte[] payload) {
        String json = new String(payload, StandardCharsets.UTF_8);
        List<com.networknt.schema.Error> schemaErrors;
        try {
            schemaErrors = schema.validate(
                    json,
                    InputFormat.JSON,
                    context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))
            );
        } catch (RuntimeException exception) {
            throw new UsageEventValidationException(
                    ValidationStage.ENVELOPE,
                    List.of("INVALID_JSON"),
                    exception
            );
        }
        if (!schemaErrors.isEmpty()) {
            throw new UsageEventValidationException(
                    ValidationStage.SCHEMA,
                    schemaErrors.stream().map(Object::toString).toList()
            );
        }

        CloudEvent cloudEvent;
        List<UsageRecord> records;
        try {
            cloudEvent = cloudEventFormat.deserialize(payload);
            records = objectMapper.readValue(
                    Objects.requireNonNull(cloudEvent.getData()).toBytes(),
                    new TypeReference<>() {
                    }
            );
        } catch (RuntimeException | IOException exception) {
            throw new UsageEventValidationException(
                    ValidationStage.ENVELOPE,
                    List.of("CLOUDEVENT_DESERIALIZATION_FAILED"),
                    exception
            );
        }

        InstanceUsageEvent event = new InstanceUsageEvent(
                UUID.fromString(cloudEvent.getId()),
                cloudEvent.getSource(),
                cloudEvent.getSubject(),
                cloudEvent.getTime(),
                records
        );
        List<String> semanticViolations = semanticValidator.validate(event);
        if (!semanticViolations.isEmpty()) {
            throw new UsageEventValidationException(ValidationStage.SEMANTIC, semanticViolations);
        }
        return event;
    }

    private Schema loadSchema() {
        try (InputStream input = InstanceUsageEventParser.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("event schema resource is missing");
            }
            String schemaJson = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12
            );
            Schema loaded = registry.getSchema(schemaJson);
            loaded.initializeValidators();
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load event schema", exception);
        }
    }
}
