package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.SnapshotStore;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader.*;
import java.util.*;
public final class PostgresSnapshotStore implements SnapshotStore {
    private final Sql sql;
    public PostgresSnapshotStore(JdbcTransactions tx) {this.sql=new Sql(tx);}
    @Override public Optional<Head> head(Query q) {
        return sql.list("""
            select s.*, (select issue_id from billing.occupancy_issue i where i.source=s.source
                and i.status='OPEN' and i.blocking and (i.scope_from is null or i.scope_from < ?)
                and (i.scope_to is null or i.scope_to > ?) order by i.created_at,i.issue_id limit 1) conflict
            from billing.occupancy_stream s where s.source=?
            """,r->new Head(r.getString("source"),r.getString("subject"),Sql.instant(r,"baseline_at"),Sql.instant(r,"confirmed_through"),r.getLong("version"),r.getBoolean("initialized"),r.getObject("conflict",UUID.class)),q.to(),q.from(),q.source()).stream().findFirst();
    }
    @Override public List<Slice> slices(Query q) {
        return sql.list("""
            select * from billing.occupancy_interval where source=? and started_at < ?
            and (ended_at is null or ended_at > ?) order by started_at,occupancy_id
            """,r->new Slice(r.getObject("occupancy_id",UUID.class),r.getString("billing_account_id"),Sql.instant(r,"started_at"),Sql.instant(r,"ended_at")),q.source(),q.to(),q.from());
    }
}
