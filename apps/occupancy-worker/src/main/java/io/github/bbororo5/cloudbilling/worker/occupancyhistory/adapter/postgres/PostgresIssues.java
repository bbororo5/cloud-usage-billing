package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import java.time.Instant;
import java.util.UUID;

final class PostgresIssues {
  private final Sql sql;

  PostgresIssues(Sql sql) {
    this.sql = sql;
  }

  void open(
      String dedup,
      String source,
      UUID event,
      String reason,
      boolean retryable,
      boolean blocking,
      Instant from,
      String topic,
      Integer partition,
      Long offset) {
    int inserted =
        sql.update(
            """
            insert into billing.occupancy_issue(issue_id,dedup_key,source,event_id,reason,retryable,blocking,scope_from,topic,partition,"offset")
            values(?,?,?,?,?,?,?,?,?,?,?) on conflict(dedup_key) do nothing
            """,
            UUID.randomUUID(),
            dedup,
            source,
            event,
            reason,
            retryable,
            blocking,
            from,
            topic,
            partition,
            offset);
    if (inserted > 0 && source != null && blocking) bump(source);
  }

  void bump(String source) {
    sql.update(
        "update billing.occupancy_stream set version=case when version < 9223372036854775807 then"
            + " version+1 else version end where source=?",
        source);
  }

  void resolve(String source, UUID event) {
    var ids =
        sql.list(
            """
            update billing.occupancy_issue set status='RESOLVED'
            where source=? and event_id=? and status='OPEN' and retryable returning issue_id
            """,
            r -> r.getObject(1, UUID.class),
            source,
            event);
    for (var id : ids)
      sql.update(
          "insert into billing.occupancy_issue_action(action_id,issue_id,operator,reason,result)"
              + " values(?,?,'worker','Application succeeded','RESOLVED')",
          UUID.randomUUID(),
          id);
  }
}
