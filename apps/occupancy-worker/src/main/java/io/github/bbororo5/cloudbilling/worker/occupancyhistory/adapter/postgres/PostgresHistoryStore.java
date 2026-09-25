package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class PostgresHistoryStore implements ReceiptStore, HistoryStore {
    private final JdbcTransactions tx;
    private final Sql sql;
    private final PostgresIssues issues;
    public PostgresHistoryStore(JdbcTransactions tx) {this.tx=tx;this.sql=new Sql(tx);this.issues=new PostgresIssues(sql);}
    @Override public void preserve(Input input) {
        var r=input.record(); var e=input.event();
        if(e!=null) {
            sql.update("insert into billing.occupancy_stream(source) values(?) on conflict do nothing",e.source());
            sql.list("select source from billing.occupancy_stream where source=? for update",row->row.getString(1),e.source());
        }
        var headers=new ObjectMapper().createArrayNode();
        for(var h:r.headers()) {var n=headers.addObject();n.put("key",h.key());n.put("value",h.value()==null?null:Base64.getEncoder().encodeToString(h.value()));}
        int added=sql.update("""
            insert into billing.occupancy_receipt(topic,partition,"offset",key_bytes,headers,raw_bytes,parse_result,source,event_id)
            values(?,?,?,?,?::jsonb,?,?,?,?) on conflict do nothing
            """,r.topic(),r.partition(),r.offset(),r.key(),headers.toString(),r.bytes(),input.error()==null?"VALID":input.error(),e==null?null:e.source(),e==null?null:e.id());
        if(added==0) return;
        if(e==null || input.error()!=null) {
            issues.open("receipt:"+r.topic()+":"+r.partition()+":"+r.offset(),e==null?null:e.source(),e==null?null:e.id(),
                input.error()==null?"INVALID_INPUT":input.error(),false,true,null,r.topic(),r.partition(),r.offset());
            return;
        }
        var existing=sql.list("select content::text from billing.occupancy_event where source=? and (id=? or sequence=?)",
                row->EventJson.decode(row.getString(1)),e.source(),e.id(),e.sequence());
        if(!existing.isEmpty()) {
            if(existing.stream().anyMatch(old->!old.equals(e))) issues.open("identity:"+e.source()+":"+e.id()+":"+e.sequence(),
                    e.source(),e.id(),"CONTENT_CONFLICT",false,true,null,r.topic(),r.partition(),r.offset());
            return;
        }
        sql.update("insert into billing.occupancy_event(source,id,sequence,kind,content) values(?,?,?,?,?::jsonb)",e.source(),e.id(),e.sequence(),e.kind().name(),EventJson.encode(e));
        sql.update("update billing.occupancy_stream set next_retry_at=now() where source=?",e.source());
    }
    @Override public List<String> due(int limit) {
        return sql.list("select source from billing.occupancy_stream where next_retry_at<=now() order by last_attempt_at,source limit ?",r->r.getString(1),limit);
    }
    @Override public Optional<Pending> lockAndLoadNext(String source) {
        var states=sql.list("select * from billing.occupancy_stream where source=? for update",r->new HistoryState(
                r.getBoolean("initialized"),r.getString("subject"),Sql.instant(r,"baseline_at"),Sql.instant(r,"confirmed_through"),
                r.getLong("last_applied_sequence"),r.getLong("version"),null,false,true),source);
        if(states.isEmpty()) return Optional.empty();
        sql.update("update billing.occupancy_stream set last_attempt_at=clock_timestamp() where source=?",source);
        var head=states.getFirst();
        boolean blocked=!sql.list("select issue_id from billing.occupancy_issue where source=? and status='OPEN' and blocking and not retryable limit 1",r->r.getString(1),source).isEmpty();
        var events=sql.list("select content::text,state from billing.occupancy_event where source=? and sequence>? order by sequence limit 1",r->Map.entry(EventJson.decode(r.getString(1)),r.getString(2)),source,head.sequence());
        if(blocked || events.isEmpty() || events.getFirst().getValue().equals("ERROR")) {
            sql.update("update billing.occupancy_stream set next_retry_at='infinity' where source=?",source);
            return Optional.empty();
        }
        var e=events.getFirst().getKey();
        var open=sql.list("select * from billing.occupancy_interval where source=? and ended_at is null",r->new HistoryState.Interval(r.getObject("occupancy_id",UUID.class),r.getString("billing_account_id"),Sql.instant(r,"started_at")),source);
        boolean used=e.occupancyId()!=null&&!sql.list("select 1 from billing.occupancy_interval where source=? and occupancy_id=?",r->true,source,e.occupancyId()).isEmpty();
        boolean account=e.account()==null||!sql.list("select billing_account_id from billing.billing_account where billing_account_id=?",r->true,e.account()).isEmpty();
        return Optional.of(new Pending(new HistoryState(head.initialized(),head.subject(),head.baseline(),head.through(),head.sequence(),head.version(),open.isEmpty()?null:open.getFirst(),used,account),e));
    }
    @Override public void persist(String source,Decision decision) {
        if(decision instanceof Decision.Wait w) {
            sql.update("update billing.occupancy_event set state='WAITING',reason=? where source=? and state in ('RECEIVED','WAITING')",w.reason(),source);
            sql.update("""
                update billing.occupancy_stream set waiting_since=coalesce(waiting_since,now()), attempts=least(attempts+1,30),
                next_retry_at=now() + (least(30,power(2,least(attempts,5))) * (0.8+random()*0.4)) * interval '1 second' where source=?
                """,source);
            return;
        }
        if(decision instanceof Decision.Reject rejected) {
            var e=sql.list("select content::text from billing.occupancy_event where source=? and sequence>(select last_applied_sequence from billing.occupancy_stream where source=?) order by sequence limit 1",r->EventJson.decode(r.getString(1)),source,source).getFirst();
            reject(e,rejected.reason());return;
        }
        if(!(decision instanceof Decision.Apply a)) return;
        Event e=a.event();
        Savepoint save;
        try {save=tx.connection().setSavepoint();} catch(SQLException ex) {throw new JdbcTransactions.StorageFailure(ex);}
        try {
            long version=sql.list("select version from billing.occupancy_stream where source=?",r->r.getLong(1),source).getFirst()+1;
            switch(e.kind()) {
                case INITIALIZED -> {
                    sql.update("update billing.occupancy_stream set initialized=true,subject=?,baseline_at=?,confirmed_through=? where source=?",e.subject(),e.baseline(),e.baseline(),source);
                    if(e.occupancyId()!=null) insertInterval(e,e.initialStart(),version);
                }
                case STARTED -> insertInterval(e,e.time(),version);
                case ENDED -> sql.update("update billing.occupancy_interval set ended_at=?,changed_version=? where source=? and occupancy_id=?",e.time(),version,source,e.occupancyId());
                case CONFIRMED -> sql.update("update billing.occupancy_stream set confirmed_through=? where source=?",e.through(),source);
            }
            sql.update("update billing.occupancy_stream set version=?,last_applied_sequence=?,next_retry_at=now(),attempts=0,waiting_since=null where source=?",version,e.sequence(),source);
            sql.update("update billing.occupancy_event set state='APPLIED',reason=null where source=? and id=?",source,e.id());
            issues.resolve(source,e.id());
            // Sequence gaps resolve only when every fact up to their recorded event has applied.
            var gapIds=sql.list("""
                update billing.occupancy_issue i set status='RESOLVED' where i.source=? and i.reason='SEQUENCE_GAP'
                and i.status='OPEN' and exists(select 1 from billing.occupancy_event e where e.source=i.source and e.id=i.event_id and e.state='APPLIED') returning issue_id
                """,r->r.getObject(1,UUID.class),source);
            for(var id:gapIds) sql.update("insert into billing.occupancy_issue_action(action_id,issue_id,operator,reason,result) values(?,?,'worker','Gap filled','RESOLVED')",UUID.randomUUID(),id);
            tx.connection().releaseSavepoint(save);
        } catch(JdbcTransactions.StorageFailure failure) {
            String state=((SQLException)failure.getCause()).getSQLState();
            if(!Set.of("23503","23505","23514","23P01").contains(state)) throw failure;
            try {tx.connection().rollback(save);}catch(SQLException ex){throw new JdbcTransactions.StorageFailure(ex);}
            reject(e,"STORAGE_CONSTRAINT");
        } catch(SQLException ex) {throw new JdbcTransactions.StorageFailure(ex);}
    }
    private void insertInterval(Event e,Instant start,long version) {
        sql.update("insert into billing.occupancy_interval(source,occupancy_id,billing_account_id,started_at,changed_version) values(?,?,?,?,?)",e.source(),e.occupancyId(),e.account(),start,version);
    }
    private void reject(Event e,String reason) {
        sql.update("update billing.occupancy_event set state='ERROR',reason=? where source=? and id=?",reason,e.source(),e.id());
        sql.update("update billing.occupancy_stream set next_retry_at='infinity' where source=?",e.source());
        issues.open("apply:"+e.source()+":"+e.id(),e.source(),e.id(),reason,reason.equals("UNKNOWN_ACCOUNT"),true,
                e.kind()==Event.Kind.INITIALIZED?e.baseline():e.time(),null,null,null);
    }
}
