package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import com.fasterxml.jackson.databind.*;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Uses the restricted function, not direct table privileges, on the attribution connection. */
public final class PostgresHistoryGuard implements HistoryGuard {
  private final JdbcTransactions tx;
  private final ObjectMapper json = new ObjectMapper();

  public PostgresHistoryGuard(JdbcTransactions tx) {
    this.tx = tx;
  }

  public <T> T locked(Query query, Function<Result, T> work) {
    return tx.write(
        () -> {
          Result result;
          try {
            String body =
                new Sql(tx)
                    .list(
                        "select billing.lock_occupancy_history(?,?,?)",
                        r -> r.getString(1),
                        query.source(),
                        query.from(),
                        query.to())
                    .getFirst();
            var n = json.readTree(body);
            result =
                switch (n.path("state").asText()) {
                  case "CONFLICT" -> new Conflict(UUID.fromString(n.path("issue").asText()));
                  case "CONFIRMED" -> {
                    var slices = new ArrayList<Slice>();
                    for (var s : n.path("intervals"))
                      slices.add(
                          new Slice(
                              UUID.fromString(s.path("id").asText()),
                              s.path("account").asText(),
                              time(s, "from"),
                              s.path("to").isNull() ? null : time(s, "to")));
                    yield new Confirmed(
                        new Snapshot(
                            n.path("source").asText(),
                            n.path("subject").asText(),
                            time(n, "baseline"),
                            time(n, "through"),
                            n.path("version").asLong(),
                            slices));
                  }
                  default -> new NotReady(Reason.valueOf(n.path("state").asText()));
                };
          } catch (Exception e) {
            throw new HistoryUnavailable(e);
          }
          return work.apply(result);
        });
  }

  private static Instant time(JsonNode n, String key) {
    return Instant.parse(n.path(key).asText());
  }
}
