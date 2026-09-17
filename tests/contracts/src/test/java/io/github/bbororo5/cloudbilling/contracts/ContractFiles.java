package io.github.bbororo5.cloudbilling.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.nio.file.Path;
import java.util.List;

final class ContractFiles {
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode read(String relativePath) {
        try {
            ObjectMapper mapper = relativePath.endsWith(".yaml")
                    ? new ObjectMapper(new YAMLFactory()) : JSON;
            return mapper.readTree(Path.of(System.getProperty("contracts.dir"), relativePath).toFile());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read contract: " + relativePath, exception);
        }
    }

    static Schema schema(JsonNode definition) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(definition.toString());
        schema.initializeValidators();
        return schema;
    }

    static List<com.networknt.schema.Error> errors(Schema schema, JsonNode value) {
        return schema.validate(value.toString(), InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    }

    private ContractFiles() { }
}
