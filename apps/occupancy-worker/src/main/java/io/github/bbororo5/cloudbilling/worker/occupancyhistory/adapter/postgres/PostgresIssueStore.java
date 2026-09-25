package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.IssueStore;
import java.util.*;

public final class PostgresIssueStore implements IssueStore {
  private final Sql sql;

  public PostgresIssueStore(JdbcTransactions tx) {
    this.sql = new Sql(tx);
  }

  private record Action(UUID issue, String operator, String reason, String result) {}

  private record Issue(String source, UUID event, boolean retryable, String status) {}

  @Override
  public Result retry(Command cmd, OperatorContext operator) {
    // Serialize identical request IDs even when maliciously reused for different issues.
    sql.list(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        r -> true,
        cmd.requestId().toString());
    var previous =
        sql.list(
            "select * from billing.occupancy_issue_action where request_id=?",
            r ->
                new Action(
                    r.getObject("issue_id", UUID.class),
                    r.getString("operator"),
                    r.getString("reason"),
                    r.getString("result")),
            cmd.requestId());
    if (!previous.isEmpty()) {
      var p = previous.getFirst();
      return p.issue().equals(cmd.issueId())
              && p.operator().equals(operator.principal())
              && p.reason().equals(cmd.reason())
          ? result(p.result())
          : new Rejected(Reason.INVALID_REQUEST);
    }
    var sources =
        sql.list(
            "select source from billing.occupancy_issue where issue_id=?",
            r -> Optional.ofNullable(r.getString(1)),
            cmd.issueId());
    if (sources.isEmpty()) return new Rejected(Reason.NOT_FOUND);
    if (sources.getFirst().isPresent())
      sql.list(
          "select source from billing.occupancy_stream where source=? for update",
          r -> true,
          sources.getFirst().get());
    var i =
        sql.list(
                "select * from billing.occupancy_issue where issue_id=? for update",
                r ->
                    new Issue(
                        r.getString("source"),
                        r.getObject("event_id", UUID.class),
                        r.getBoolean("retryable"),
                        r.getString("status")),
                cmd.issueId())
            .getFirst();
    String result;
    if (i.status().equals("RESOLVED")) result = "ALREADY_RESOLVED";
    else if (!i.retryable() || i.event() == null) result = "INVALID_REQUEST";
    else {
      int updated =
          sql.update(
              "update billing.occupancy_event set state='RECEIVED',reason=null where source=? and"
                  + " id=? and state='ERROR'",
              i.source(),
              i.event());
      sql.update(
          "update billing.occupancy_stream set next_retry_at=now(),attempts=0 where source=?",
          i.source());
      result = updated > 0 ? "SCHEDULED" : "ALREADY_SCHEDULED";
    }
    sql.update(
        "insert into"
            + " billing.occupancy_issue_action(action_id,request_id,issue_id,operator,reason,result)"
            + " values(?,?,?,?,?,?)",
        UUID.randomUUID(),
        cmd.requestId(),
        cmd.issueId(),
        operator.principal(),
        cmd.reason(),
        result);
    return result(result);
  }

  private static Result result(String result) {
    return switch (result) {
      case "SCHEDULED" -> new Scheduled();
      case "ALREADY_SCHEDULED" -> new AlreadyScheduled();
      default -> new Rejected(Reason.valueOf(result));
    };
  }

  @Override
  public List<Alert> claimAlerts(int limit) {
    return sql.list(
        """
        with pending as (select issue_id from billing.occupancy_issue where alert_state='PENDING'
            and next_alert_at<=now() order by next_alert_at,issue_id limit ? for update skip locked)
        update billing.occupancy_issue i set alert_attempts=least(alert_attempts+1,30),
            next_alert_at=now()+interval '30 seconds' from pending p where i.issue_id=p.issue_id
        returning i.issue_id,i.reason
        """,
        r -> new Alert(r.getObject(1, UUID.class), r.getString(2)),
        limit);
  }

  @Override
  public void delivered(UUID id) {
    sql.update("update billing.occupancy_issue set alert_state='SENT' where issue_id=?", id);
  }

  public void flagLongWaits(int seconds, int limit) {
    var sources =
        sql.list(
            "select source from billing.occupancy_stream where waiting_since < now() - ? * interval"
                + " '1 second' order by waiting_since limit ? for update skip locked",
            r -> r.getString(1),
            seconds,
            limit);
    var helper = new PostgresIssues(sql);
    for (var source : sources) {
      var events =
          sql.list(
              "select id from billing.occupancy_event where source=? and state='WAITING' order by"
                  + " sequence limit 1",
              r -> r.getObject(1, UUID.class),
              source);
      if (!events.isEmpty())
        helper.open(
            "gap:" + source + ":" + events.getFirst(),
            source,
            events.getFirst(),
            "SEQUENCE_GAP",
            true,
            false,
            null,
            null,
            null,
            null);
    }
  }
}
