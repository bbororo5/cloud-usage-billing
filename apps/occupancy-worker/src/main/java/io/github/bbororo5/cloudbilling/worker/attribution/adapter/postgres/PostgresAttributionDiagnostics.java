package io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import java.util.*;

public final class PostgresAttributionDiagnostics {
  private final JdbcTransactions tx;
  private final Sql sql;

  public PostgresAttributionDiagnostics(JdbcTransactions tx) {
    this.tx = tx;
    sql = new Sql(tx);
  }

  public void validate() {
    tx.read(
        () -> {
          boolean unsafe =
              sql.list(
                      """
                      select current_user<>'billing_attribution' or rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication
                        or has_table_privilege(current_user,'billing.occupancy_stream','UPDATE')
                        or has_table_privilege(current_user,'billing.attribution_attempt','UPDATE')
                        or has_schema_privilege(current_user,'billing','CREATE')
                        or exists(select 1 from pg_auth_members where member=pg_roles.oid)
                      from pg_roles where rolname=current_user
                      """,
                      r -> r.getBoolean(1))
                  .getFirst();
          if (unsafe) throw new IllegalStateException("Unsafe attribution database role");
          sql.list("select id from billing.attribution_scan", r -> r.getInt(1));
          return null;
        });
  }

  public Map<String, Double> measurements() {
    return tx.read(
        () -> {
          var m = new LinkedHashMap<String, Double>();
          sql.list(
              "select state,count(*) from billing.attribution_job group by state",
              r -> {
                m.put("jobs." + r.getString(1).toLowerCase(Locale.ROOT), (double) r.getLong(2));
                return true;
              });
          for (String state : List.of("pending", "waiting", "prepared", "approved", "error"))
            m.putIfAbsent("jobs." + state, 0.0);
          sql.list(
              "select coalesce(extract(epoch from clock_timestamp()-min(discovered_at)),0) from"
                  + " billing.attribution_job where state='WAITING'",
              r -> {
                m.put("waiting.oldest.seconds", r.getDouble(1));
                return true;
              });
          sql.list(
              "select count(*) filter(where status='OPEN'),count(*) filter(where status='OPEN' and"
                  + " not alert_sent) from billing.attribution_issue",
              r -> {
                m.put("issues.open", r.getDouble(1));
                m.put("alerts.pending", r.getDouble(2));
                return true;
              });
          sql.list(
              "select rows_read,new_jobs,extract(epoch from"
                  + " coalesce(completed_at,clock_timestamp())-started_at) from"
                  + " billing.attribution_scan",
              r -> {
                m.put("sweep.rows", r.getDouble(1));
                m.put("sweep.new", r.getDouble(2));
                m.put("sweep.seconds", Math.max(0, r.getDouble(3)));
                return true;
              });
          return m;
        });
  }
}
