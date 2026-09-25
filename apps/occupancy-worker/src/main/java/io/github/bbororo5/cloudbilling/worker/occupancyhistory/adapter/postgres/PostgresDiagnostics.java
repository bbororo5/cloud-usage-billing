package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;

import java.util.*;

public final class PostgresDiagnostics {
  private final JdbcTransactions tx;
  private final Sql sql;

  public PostgresDiagnostics(JdbcTransactions tx) {
    this.tx = tx;
    sql = new Sql(tx);
  }

  public void validate() {
    tx.read(
        () -> {
          boolean safe =
              sql.list(
                      """
                      select current_user='billing_occupancy' and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls)
                      from pg_roles where rolname=current_user
                      """,
                      r -> r.getBoolean(1))
                  .getFirst();
          if (!safe) throw new IllegalStateException("Unsafe occupancy database identity");
          for (String table :
              List.of(
                  "occupancy_receipt",
                  "occupancy_event",
                  "occupancy_stream",
                  "occupancy_interval",
                  "occupancy_issue",
                  "occupancy_issue_action")) {
            boolean access =
                sql.list(
                        "select has_table_privilege(current_user,?,'SELECT') and"
                            + " has_table_privilege(current_user,?,'INSERT') and not"
                            + " has_table_privilege(current_user,?,'DELETE')",
                        r -> r.getBoolean(1),
                        "billing." + table,
                        "billing." + table,
                        "billing." + table)
                    .getFirst();
            if (!access)
              throw new IllegalStateException("Invalid worker table privileges: " + table);
          }
          int constraints =
              sql.list(
                      "select count(*) from pg_constraint where"
                          + " conrelid='billing.occupancy_interval'::regclass and contype='x' and"
                          + " convalidated",
                      r -> r.getInt(1))
                  .getFirst();
          boolean forbidden =
              sql.list(
                      """
                      select has_schema_privilege(current_user,'billing','CREATE')
                      or has_table_privilege(current_user,'billing.billing_membership','SELECT,INSERT,UPDATE,DELETE')
                      or has_column_privilege(current_user,'billing.billing_account','billing_account_name','SELECT')
                      """,
                      r -> r.getBoolean(1))
                  .getFirst();
          boolean durable =
              sql.list(
                      "select current_setting('fsync')='on' and"
                          + " current_setting('full_page_writes')='on'",
                      r -> r.getBoolean(1))
                  .getFirst();
          if (constraints != 1 || forbidden || !durable)
            throw new IllegalStateException("Missing isolation or durability prerequisite");
          return null;
        });
  }

  public Map<String, Double> measurements() {
    return tx.read(
        () -> {
          var values = new LinkedHashMap<String, Double>();
          values.put(
              "pending",
              sql.list(
                      "select count(*) from billing.occupancy_event where state <> 'APPLIED'",
                      r -> r.getDouble(1))
                  .getFirst());
          values.put(
              "oldest.pending.seconds",
              sql.list(
                      "select coalesce(extract(epoch from now()-min(received_at)),0) from"
                          + " billing.occupancy_event where state <> 'APPLIED'",
                      r -> r.getDouble(1))
                  .getFirst());
          values.put(
              "open.issues",
              sql.list(
                      "select count(*) from billing.occupancy_issue where status='OPEN'",
                      r -> r.getDouble(1))
                  .getFirst());
          values.put(
              "pending.alerts",
              sql.list(
                      "select count(*) from billing.occupancy_issue where alert_state='PENDING'",
                      r -> r.getDouble(1))
                  .getFirst());
          values.put(
              "confirmation.lag.seconds",
              sql.list(
                      "select coalesce(extract(epoch from now()-min(confirmed_through)),0) from"
                          + " billing.occupancy_stream where initialized",
                      r -> r.getDouble(1))
                  .getFirst());
          values.put(
              "database.bytes",
              sql.list("select pg_database_size(current_database())", r -> r.getDouble(1))
                  .getFirst());
          return values;
        });
  }
}
