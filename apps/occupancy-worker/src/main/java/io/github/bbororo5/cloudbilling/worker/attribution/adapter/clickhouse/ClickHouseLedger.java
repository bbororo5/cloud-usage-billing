package io.github.bbororo5.cloudbilling.worker.attribution.adapter.clickhouse;

import com.fasterxml.jackson.databind.*;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.JsonCodec;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Parameterized HTTP queries; finite deadlines, synchronous inserts, no ingestion consumer. */
public final class ClickHouseLedger implements UsageLedger, ResultLedger {
  private final URI endpoint;
  private final String user, password;
  private final HttpClient client;

  public ClickHouseLedger(URI endpoint, String user, String password) {
    if (!Set.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getRawQuery() != null
        || endpoint.getUserInfo() != null)
      throw new IllegalArgumentException("Invalid ClickHouse endpoint");
    this.endpoint = endpoint;
    this.user = Objects.requireNonNull(user);
    this.password = Objects.requireNonNull(password);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  public String execute(String sql, Map<String, String> parameters) {
    var uri =
        new StringBuilder(endpoint.toString())
            .append(
                "/?date_time_output_format=iso&date_time_input_format=best_effort&output_format_json_quote_64bit_integers=1&async_insert=0&wait_end_of_query=1&max_execution_time=5");
    parameters.forEach(
        (k, v) -> uri.append("&param_").append(encode(k)).append("=").append(encode(v)));
    var request =
        HttpRequest.newBuilder(URI.create(uri.toString()))
            .timeout(Duration.ofSeconds(8))
            .header("X-ClickHouse-User", user)
            .header("X-ClickHouse-Key", password)
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException(
            "ClickHouse query failed (HTTP "
                + response.statusCode()
                + ", code "
                + response.headers().firstValue("X-ClickHouse-Exception-Code").orElse("unknown")
                + ")");
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ClickHouse interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("ClickHouse unavailable", e);
    }
  }

  private List<JsonNode> rows(String sql, Map<String, String> params) {
    return execute(sql + " FORMAT JSONEachRow", params)
        .lines()
        .filter(s -> !s.isBlank())
        .map(JsonCodec::parse)
        .toList();
  }

  private static Usage.Key key(JsonNode n) {
    return new Usage.Key(
        n.path("event_source").asText(), UUID.fromString(n.path("event_id").asText()));
  }

  public Optional<Usage.Key> upperBound() {
    return rows(
            "select event_source,event_id from billing.usage_event order by event_source"
                + " desc,event_id desc limit 1",
            Map.of())
        .stream()
        .map(ClickHouseLedger::key)
        .findFirst();
  }

  public List<Usage> page(Usage.Key after, Usage.Key upper, int limit) {
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid page limit");
    var params = new HashMap<String, String>();
    params.put("upperSource", upper.source());
    params.put("upperId", upper.id().toString());
    String lower = "";
    if (after != null) {
      lower = " and (event_source,event_id)>({afterSource:String},{afterId:UUID})";
      params.put("afterSource", after.source());
      params.put("afterId", after.id().toString());
    }
    return rows(
            "select *,arrayMap(m -> (m.1,toString(m.2),m.3),measurements) as values_ from"
                + " billing.usage_event where"
                + " (event_source,event_id)<=({upperSource:String},{upperId:UUID})"
                + lower
                + " order by event_source,event_id limit "
                + limit,
            params)
        .stream()
        .map(
            n -> {
              var measurements = new ArrayList<Usage.Measurement>();
              for (var m : n.path("values_"))
                measurements.add(
                    new Usage.Measurement(
                        m.get(0).asText(),
                        new java.math.BigInteger(m.get(1).asText()),
                        m.get(2).asText()));
              return new Usage(
                  key(n),
                  n.path("event_subject").asText(),
                  Instant.parse(n.path("charge_period_start").asText()),
                  Instant.parse(n.path("charge_period_end").asText()),
                  n.path("region_id").asText(),
                  n.path("resource_id").asText(),
                  n.path("resource_type").asText(),
                  measurements);
            })
        .toList();
  }

  public void append(Prepared p) {
    var n = new ObjectMapper().createObjectNode();
    n.put("event_source", p.usage().key().source());
    n.put("event_id", p.usage().key().id().toString());
    n.put("revision", p.revision().toString());
    n.put("payload", JsonCodec.prepared(p));
    execute(
        "insert into billing.attribution_delivery(event_source,event_id,revision,payload) FORMAT"
            + " JSONEachRow\n"
            + n
            + "\n",
        Map.of());
  }

  public Optional<Prepared> read(Usage.Key key, UUID revision) {
    var found =
        rows(
            "select payload,variants from billing.attribution_revision where"
                + " event_source={source:String} and event_id={id:UUID} and"
                + " revision={revision:UUID}",
            Map.of(
                "source",
                key.source(),
                "id",
                key.id().toString(),
                "revision",
                revision.toString()));
    if (found.isEmpty()) return Optional.empty();
    if (found.size() != 1 || found.getFirst().path("variants").asLong() != 1)
      throw new RevisionConflict();
    var p = JsonCodec.prepared(found.getFirst().path("payload").asText());
    if (!p.usage().key().equals(key) || !p.revision().equals(revision))
      throw new RevisionConflict();
    return Optional.of(p);
  }
}
