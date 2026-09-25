package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.ReceiptStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class EventDecoder {
  private final ObjectMapper json =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final Schema schema;

  public EventDecoder() {
    try (var in = getClass().getResourceAsStream("/v1/instance-occupancy-event.schema.json")) {
      if (in == null) throw new IllegalStateException("Missing occupancy schema");
      schema =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
              .getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      schema.initializeValidators();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load occupancy schema", e);
    }
  }

  public ReceiptStore.Input decode(ReceiptStore.Record record) {
    try {
      if (record.bytes() == null) return new ReceiptStore.Input(record, null, "INVALID_WIRE");
      JsonNode n = json.readTree(record.bytes());
      if (n == null
          || !schema
              .validate(
                  n.toString(),
                  InputFormat.JSON,
                  c -> c.executionConfig(x -> x.formatAssertionsEnabled(true)))
              .isEmpty()) return new ReceiptStore.Input(record, null, "INVALID_WIRE");
      var d = n.get("data");
      String kind =
          n.get("type")
              .asText()
              .replace("io.github.bbororo5.cloudusage.instance.occupancy.", "")
              .replace(".v1", "");
      var k = Event.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
      var o = k == Event.Kind.INITIALIZED ? d.get("initialOccupancy") : d;
      UUID occupancy =
          o == null || o.isNull() || !o.has("occupancyId")
              ? null
              : UUID.fromString(o.get("occupancyId").asText());
      String account =
          o == null || o.isNull() || !o.has("billingAccountId")
              ? null
              : o.get("billingAccountId").asText();
      Event event =
          new Event(
              n.get("source").asText(),
              n.get("subject").asText(),
              UUID.fromString(n.get("id").asText()),
              k,
              d.get("sequence").longValue(),
              instant(n, "time"),
              occupancy,
              account,
              instant(d, "baselineAt"),
              instant(d, "confirmedThrough"),
              k == Event.Kind.INITIALIZED && o != null && !o.isNull()
                  ? instant(o, "startedAt")
                  : null);
      if (!Arrays.equals(record.key(), event.source().getBytes(StandardCharsets.UTF_8)))
        return new ReceiptStore.Input(record, event, "KEY_SOURCE_MISMATCH");
      return new ReceiptStore.Input(record, event, null);
    } catch (Exception invalid) {
      return new ReceiptStore.Input(record, null, "INVALID_WIRE");
    }
  }

  private static Instant instant(JsonNode n, String field) {
    if (!n.has(field)) return null;
    Instant t = Instant.parse(n.get(field).asText());
    if (t.getNano() % 1_000_000 != 0)
      throw new IllegalArgumentException("Millisecond precision required");
    return t;
  }
}
