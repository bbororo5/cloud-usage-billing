package io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.attribution.adapter.JsonCodec;
import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionRetry.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.OperationsStore;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import java.util.*;

public final class PostgresOperations implements OperationsStore {
  private final Sql sql;

  public PostgresOperations(JdbcTransactions tx) {
    sql = new Sql(tx);
  }

  public Result retry(Command c, Operator actor) {
    // Serialize a request ID even when two calls target different events.
    sql.list(
        "select pg_advisory_xact_lock(hashtextextended(?::text,0))",
        r -> true,
        c.requestId().toString());
    var previous =
        sql.list(
            "select * from billing.attribution_action where request_id=?",
            r -> {
              if (!c.source().equals(r.getString("source"))
                  || !c.eventId().equals(r.getObject("event_id", UUID.class))
                  || !actor.principal().equals(r.getString("operator"))
                  || !c.reason().equals(r.getString("reason")))
                throw new IllegalArgumentException("Request ID reused with different command");
              return r.getString("result");
            },
            c.requestId());
    if (!previous.isEmpty())
      return previous.getFirst().equals("SCHEDULED") ? Result.ALREADY_SCHEDULED : Result.REJECTED;
    var jobs =
        sql.list(
            "select state,reason from billing.attribution_job where source=? and event_id=? for"
                + " update",
            r -> List.of(r.getString("state"), Objects.toString(r.getString("reason"), "")),
            c.source(),
            c.eventId());
    if (jobs.isEmpty()) return Result.REJECTED;
    var j = jobs.getFirst();
    boolean permitted =
        j.getFirst().equals("ERROR")
            && !Set.of("MONTH_FINALIZED", "RESULT_CONFLICT", "OWNERSHIP_CHANGED")
                .contains(j.getLast());
    if (permitted)
      sql.update(
          "update billing.attribution_job set"
              + " state='PENDING',prepared_revision=null,token=null,lease_until=null,due_at=clock_timestamp()"
              + " where source=? and event_id=?",
          c.source(),
          c.eventId());
    String result = permitted ? "SCHEDULED" : "REJECTED";
    sql.update(
        "insert into billing.attribution_action(request_id,source,event_id,operator,reason,result)"
            + " values(?,?,?,?,?,?)",
        c.requestId(),
        c.source(),
        c.eventId(),
        actor.principal(),
        c.reason(),
        result);
    return Result.valueOf(result);
  }

  public List<Alert> pending(int limit) {
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid alert limit");
    return sql.list(
        """
        select i.source,i.event_id,i.reason,i.alert_attempts,j.usage_json,a.account from billing.attribution_issue i
        join billing.attribution_job j using(source,event_id) left join billing.attribution_attempt a on a.revision=j.prepared_revision
        where i.status='OPEN' and not i.alert_sent and i.next_alert_at<=clock_timestamp() order by i.next_alert_at limit ?
        """,
        r -> {
          var u = JsonCodec.usage(r.getString("usage_json"));
          return new Alert(
              u.key(),
              u.from(),
              u.to(),
              r.getString("account"),
              r.getString("reason"),
              r.getInt("alert_attempts"));
        },
        limit);
  }

  public void delivered(Alert a, boolean success) {
    sql.update(
        "update billing.attribution_issue set"
            + " alert_sent=?,alert_attempts=alert_attempts+1,next_alert_at=clock_timestamp()+interval"
            + " '30 seconds' where source=? and event_id=? and reason=? and alert_attempts=? and"
            + " status='OPEN'",
        success,
        a.key().source(),
        a.key().id(),
        a.reason(),
        a.attempt());
  }
}
