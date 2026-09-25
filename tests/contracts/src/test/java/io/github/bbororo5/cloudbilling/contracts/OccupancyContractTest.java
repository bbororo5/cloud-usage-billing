package io.github.bbororo5.cloudbilling.contracts;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyContractTest {
    @Test void fourKindsAndInvalidInputs() {
        var schema = ContractFiles.schema(ContractFiles.read("v1/instance-occupancy-event.schema.json"));
        for (String kind : new String[]{"initialized", "started", "ended", "confirmed"}) {
            ObjectNode event = (ObjectNode) ContractFiles.read("examples/occupancy/" + kind + ".json");
            assertTrue(ContractFiles.errors(schema, event).isEmpty(), kind);
            var bad = event.deepCopy(); bad.remove("source");
            assertFalse(ContractFiles.errors(schema, bad).isEmpty());
            bad = event.deepCopy(); ((ObjectNode) bad.get("data")).put("sequence", 0);
            assertFalse(ContractFiles.errors(schema, bad).isEmpty());
            bad = event.deepCopy(); ((ObjectNode) bad.get("data")).put("unknown", true);
            assertFalse(ContractFiles.errors(schema, bad).isEmpty());
            bad = event.deepCopy(); bad.put("time", "2026-02-30T00:00:00Z");
            assertFalse(ContractFiles.errors(schema, bad).isEmpty());
        }
    }
}
