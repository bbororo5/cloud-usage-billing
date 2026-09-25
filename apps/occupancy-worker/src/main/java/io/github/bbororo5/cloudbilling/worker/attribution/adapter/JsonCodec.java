package io.github.bbororo5.cloudbilling.worker.attribution.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import java.time.Instant;
import java.util.*;

/** Stable prepared payload, including full UInt64 values; no floating-point conversion. */
public final class JsonCodec {
  private static final ObjectMapper JSON = new ObjectMapper();

  private JsonCodec() {}

  public static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid stored JSON", e);
    }
  }

  public static ObjectNode usageNode(Usage u) {
    var n = JSON.createObjectNode();
    n.put("source", u.key().source());
    n.put("id", u.key().id().toString());
    n.put("subject", u.subject());
    n.put("from", u.from().toString());
    n.put("to", u.to().toString());
    n.put("region", u.region());
    n.put("resource", u.resource());
    n.put("resourceType", u.resourceType());
    var measurements = n.putArray("measurements");
    for (var m : u.measurements())
      measurements
          .addObject()
          .put("meter", m.meter())
          .put("quantity", m.quantity().toString())
          .put("unit", m.unit());
    return n;
  }

  public static String usage(Usage u) {
    return usageNode(u).toString();
  }

  public static Usage usage(String json) {
    return usage(parse(json));
  }

  private static Usage usage(JsonNode n) {
    var values = new ArrayList<Usage.Measurement>();
    for (var m : n.path("measurements"))
      values.add(
          new Usage.Measurement(
              m.path("meter").asText(),
              new java.math.BigInteger(m.path("quantity").asText()),
              m.path("unit").asText()));
    return new Usage(
        new Usage.Key(n.path("source").asText(), UUID.fromString(n.path("id").asText())),
        n.path("subject").asText(),
        Instant.parse(n.path("from").asText()),
        Instant.parse(n.path("to").asText()),
        n.path("region").asText(),
        n.path("resource").asText(),
        n.path("resourceType").asText(),
        values);
  }

  public static String prepared(Prepared p) {
    var n = JSON.createObjectNode();
    n.put("revision", p.revision().toString());
    n.set("usage", usageNode(p.usage()));
    n.put("account", p.account());
    n.put("occupancy", p.occupancy().toString());
    n.put("historyVersion", p.historyVersion());
    return n.toString();
  }

  public static Prepared prepared(String json) {
    var n = parse(json);
    return new Prepared(
        UUID.fromString(n.path("revision").asText()),
        usage(n.path("usage")),
        n.path("account").asText(),
        UUID.fromString(n.path("occupancy").asText()),
        n.path("historyVersion").asLong());
  }
}
