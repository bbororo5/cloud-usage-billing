package io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.attribution.adapter.JsonCodec;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.WorkStore;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import java.sql.*;
import java.util.*;

public final class PostgresWorkStore implements WorkStore {
  private final Sql sql;

  public PostgresWorkStore(JdbcTransactions tx) {
    sql = new Sql(tx);
  }

  private static Usage.Key key(ResultSet r, String prefix) throws SQLException {
    String source = r.getString(prefix + "source");
    return source == null ? null : new Usage.Key(source, r.getObject(prefix + "id", UUID.class));
  }

  public Sweep sweep() {
    return sql.list(
            "select * from billing.attribution_scan where id=1",
            r -> new Sweep(r.getLong("generation"), key(r, "upper_"), key(r, "cursor_")))
        .getFirst();
  }

  public boolean beginSweep(Sweep expected, Usage.Key upper) {
    return sql.update(
            "update billing.attribution_scan set"
                + " upper_source=?,upper_id=?,cursor_source=null,cursor_id=null,generation=generation+1,started_at=clock_timestamp(),completed_at=null,rows_read=0,new_jobs=0"
                + " where id=1 and generation=? and upper_source is null",
            upper.source(),
            upper.id(),
            expected.generation())
        == 1;
  }

  public int register(Sweep expected, List<Usage> page) {
    var current =
        sql.list(
                "select * from billing.attribution_scan where id=1 for update",
                r -> new Sweep(r.getLong("generation"), key(r, "upper_"), key(r, "cursor_")))
            .getFirst();
    if (!current.equals(expected)) return -1;
    int inserted = 0;
    for (var u : page)
      inserted +=
          sql.update(
              "insert into billing.attribution_job(source,event_id,usage_json) values(?,?,?) on"
                  + " conflict do nothing",
              u.key().source(),
              u.key().id(),
              JsonCodec.usage(u));
    if (page.isEmpty())
      sql.update(
          "update billing.attribution_scan set"
              + " upper_source=null,upper_id=null,cursor_source=null,cursor_id=null,generation=generation+1,completed_at=clock_timestamp()"
              + " where id=1");
    else {
      var last = page.getLast().key();
      sql.update(
          "update billing.attribution_scan set"
              + " cursor_source=?,cursor_id=?,generation=generation+1,rows_read=rows_read+?,new_jobs=new_jobs+?"
              + " where id=1",
          last.source(),
          last.id(),
          page.size(),
          inserted);
    }
    return inserted;
  }

  public Optional<Claim> claim(int seconds) {
    if (seconds < 1 || seconds > 300)
      throw new IllegalArgumentException("Lease must be 1..300 seconds");
    UUID token = UUID.randomUUID();
    var result =
        sql
            .list(
                """
                with candidate as (select source,event_id from billing.attribution_job
                  where state in ('PENDING','WAITING','PREPARED') and due_at<=clock_timestamp()
                  and (lease_until is null or lease_until<=clock_timestamp()) order by due_at,source,event_id for update skip locked limit 1)
                update billing.attribution_job j set token=?,lease_until=clock_timestamp()+make_interval(secs=>?),due_at=clock_timestamp()
                from candidate c where j.source=c.source and j.event_id=c.event_id returning j.*
                """,
                r ->
                    new Claim(
                        JsonCodec.usage(r.getString("usage_json")),
                        token,
                        prepared(r.getObject("prepared_revision", UUID.class))),
                token,
                seconds)
            .stream()
            .findFirst();
    result.ifPresent(
        c -> audit(c, "CLAIMED", c.prepared() == null ? null : c.prepared().revision()));
    return result;
  }

  private Prepared prepared(UUID revision) {
    if (revision == null) return null;
    return sql.list(
            "select payload from billing.attribution_attempt where revision=?",
            r -> JsonCodec.prepared(r.getString(1)),
            revision)
        .getFirst();
  }

  public boolean owns(Claim c) {
    return !sql.list(
            "select 1 from billing.attribution_job where source=? and event_id=? and token=? and"
                + " lease_until>clock_timestamp() for update",
            r -> r.getInt(1),
            c.usage().key().source(),
            c.usage().key().id(),
            c.token())
        .isEmpty();
  }

  public boolean prepare(Claim c, Prepared p) {
    if (!owns(c)) return false;
    if (sql.list(
                "select prepared_revision from billing.attribution_job where source=? and"
                    + " event_id=?",
                r -> r.getObject(1, UUID.class),
                c.usage().key().source(),
                c.usage().key().id())
            .getFirst()
        != null) return false;
    if (!c.usage().equals(p.usage())) throw new IllegalArgumentException("Prepared input differs");
    sql.update(
        "insert into"
            + " billing.attribution_attempt(revision,source,event_id,payload,account,occupancy,history_version)"
            + " values(?,?,?,?,?,?,?)",
        p.revision(),
        p.usage().key().source(),
        p.usage().key().id(),
        JsonCodec.prepared(p),
        p.account(),
        p.occupancy(),
        p.historyVersion());
    sql.update(
        "update billing.attribution_job set prepared_revision=?,state='PREPARED',reason=null where"
            + " source=? and event_id=?",
        p.revision(),
        c.usage().key().source(),
        c.usage().key().id());
    audit(c, "PREPARED", p.revision());
    return true;
  }

  public boolean defer(Claim c, AttributionRules.Deferred outcome) {
    Objects.requireNonNull(outcome);
    var reason = outcome.reason();
    boolean error = outcome instanceof AttributionRules.Failed;
    if (!owns(c)) return false;
    sql.update(
        "update billing.attribution_job set"
            + " state=?,reason=?,token=null,lease_until=null,due_at=clock_timestamp()+interval '1"
            + " second' where source=? and event_id=?",
        error ? "ERROR" : "WAITING",
        reason,
        c.usage().key().source(),
        c.usage().key().id());
    if (error)
      sql.update(
          """
          insert into billing.attribution_issue(source,event_id,reason) values(?,?,?) on conflict(source,event_id)
          do update set reason=excluded.reason,status='OPEN',alert_sent=false,next_alert_at=clock_timestamp()
          """,
          c.usage().key().source(),
          c.usage().key().id(),
          reason);
    audit(c, (error ? "ERROR:" : "WAITING:") + reason, null);
    return true;
  }

  public boolean restart(Claim c) {
    if (!owns(c)) return false;
    sql.update(
        "update billing.attribution_job set"
            + " prepared_revision=null,state='PENDING',token=null,lease_until=null,due_at=clock_timestamp()"
            + " where source=? and event_id=?",
        c.usage().key().source(),
        c.usage().key().id());
    audit(c, "HISTORY_CHANGED", null);
    return true;
  }

  public boolean recordApproval(Claim c, Prepared p) {
    if (!owns(c)) return false;
    var matches =
        sql.list(
            "select 1 from billing.attribution_job where source=? and event_id=? and"
                + " prepared_revision=? and state='PREPARED'",
            r -> r.getInt(1),
            c.usage().key().source(),
            c.usage().key().id(),
            p.revision());
    if (matches.isEmpty()) return false;
    sql.update(
        "insert into billing.attribution_approval(source,event_id,revision) values(?,?,?)",
        p.usage().key().source(),
        p.usage().key().id(),
        p.revision());
    sql.update(
        "update billing.attribution_job set"
            + " state='APPROVED',approved_revision=?,token=null,lease_until=null,reason=null where"
            + " source=? and event_id=?",
        p.revision(),
        p.usage().key().source(),
        p.usage().key().id());
    sql.update(
        "update billing.attribution_issue set status='RESOLVED' where source=? and event_id=?",
        p.usage().key().source(),
        p.usage().key().id());
    audit(c, "APPROVED", p.revision());
    return true;
  }

  public Optional<Prepared> approved(Usage.Key key) {
    return sql
        .list(
            """
            select a.payload from billing.attribution_approval p join billing.attribution_attempt a on a.revision=p.revision
            join billing.attribution_job j on j.source=p.source and j.event_id=p.event_id and j.approved_revision=p.revision
            where p.source=? and p.event_id=? and j.state='APPROVED'
            and not exists(select 1 from billing.attribution_issue i where i.source=p.source and i.event_id=p.event_id and i.status='OPEN')
            """,
            r -> JsonCodec.prepared(r.getString(1)),
            key.source(),
            key.id())
        .stream()
        .findFirst();
  }

  public boolean monthClosed(Prepared p) {
    return sql.list(
            "select billing.attribution_month_closed(?,?,?)",
            r -> r.getBoolean(1),
            p.account(),
            p.usage().from(),
            p.usage().to())
        .getFirst();
  }

  private void audit(Claim c, String decision, UUID revision) {
    sql.update(
        "insert into"
            + " billing.attribution_transition(transition_id,source,event_id,token,decision,revision)"
            + " values(?,?,?,?,?,?)",
        UUID.randomUUID(),
        c.usage().key().source(),
        c.usage().key().id(),
        c.token(),
        decision,
        revision);
  }
}
