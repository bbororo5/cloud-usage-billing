package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PersistenceTest {
    final String source="urn:test:"+UUID.randomUUID();
    final Instant t=Instant.parse("2026-09-01T10:00:00Z");
    JdbcTransactions tx;
    PostgresHistoryStore store;
    ReceiptService receipt;
    ApplyService apply;
    @BeforeEach void setup() throws Exception {
        String url=Objects.requireNonNull(System.getenv("OCCUPANCY_TEST_URL"), "Use verify-occupancy.sh");
        tx=new JdbcTransactions(new DriverManagerDataSource(url,"billing_occupancy","local-dev-only"));
        store=new PostgresHistoryStore(tx); receipt=new ReceiptService(tx,store); apply=new ApplyService(tx,store);
        try(var c=DriverManager.getConnection(url,"billing_owner","local-dev-only"); var s=c.createStatement()) {
            s.execute("insert into billing.billing_account(billing_account_id,billing_account_name) values('x','X') on conflict do nothing");
        }
    }
    Event event(String vm,long seq, Event.Kind kind,UUID occupancy,String account) {
        return new Event(vm,"instances/a",UUID.randomUUID(),kind,seq,t.plusSeconds(seq*60),occupancy,account,
                kind==Event.Kind.INITIALIZED?t:null,kind==Event.Kind.CONFIRMED?t.plusSeconds(seq*60):null,null);
    }
    void receive(Event event,long offset) {
        receipt.receive(new ReceiptStore.Input(new ReceiptStore.Record(source,0,offset,event.source().getBytes(),List.of(),("raw-"+offset).getBytes()),event,null));
    }
    long number(String query) {
        return tx.read(() -> { try(var s=tx.connection().createStatement();var r=s.executeQuery(query)) {r.next();return r.getLong(1);} catch(SQLException e){throw new RuntimeException(e);} });
    }
    @Test void durableReplayGapAndIndependentVm() {
        var init=event(source,1,Event.Kind.INITIALIZED,null,null);
        receive(init,1); receive(init,1); receive(init,2); apply.applyNext(source);
        assertEquals(2,number("select count(*) from billing.occupancy_receipt where topic='"+source+"'"));
        assertEquals(1,number("select version from billing.occupancy_stream where source='"+source+"'"));
        var id=UUID.randomUUID();
        receive(event(source,3,Event.Kind.ENDED,id,null),3);
        assertInstanceOf(Decision.Wait.class,apply.applyNext(source));
        String other=source+"b"; receive(event(other,1,Event.Kind.INITIALIZED,null,null),4);
        assertInstanceOf(Decision.Apply.class,apply.applyNext(other));
        receive(event(source,2,Event.Kind.STARTED,id,"x"),5);
        apply.applyNext(source);apply.applyNext(source);
        assertEquals(3,number("select last_applied_sequence from billing.occupancy_stream where source='"+source+"'"));
        assertEquals(1,number("select count(*) from billing.occupancy_interval where source='"+source+"' and ended_at is not null"));
    }
    @Test void unknownAccountAndConflictingIdentityArePreserved() {
        receive(event(source,1,Event.Kind.INITIALIZED,null,null),1);apply.applyNext(source);
        var e=event(source,2,Event.Kind.STARTED,UUID.randomUUID(),"missing");receive(e,2);apply.applyNext(source);
        assertEquals(1,number("select count(*) from billing.occupancy_issue where source='"+source+"' and status='OPEN'"));
        receive(new Event(e.source(),e.subject(),e.id(),e.kind(),e.sequence(),e.time(),e.occupancyId(),"x",null,null,null),3);
        assertEquals(3,number("select count(*) from billing.occupancy_receipt where topic='"+source+"'"));
        assertEquals(0,number("select count(*) from billing.occupancy_interval where source='"+source+"'"));
    }
    @Test void simultaneousApplyAndRollback() throws Exception {
        receive(event(source,1,Event.Kind.INITIALIZED,null,null),1);
        var gate=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()-> {gate.await();return apply.applyNext(source);});
            var b=pool.submit(()-> {gate.await();return apply.applyNext(source);});
            a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,number("select version from billing.occupancy_stream where source='"+source+"'"));
        receive(event(source,2,Event.Kind.STARTED,UUID.randomUUID(),"x"),2);
        assertThrows(IllegalStateException.class,()->tx.write(()-> {
            var p=store.lockAndLoadNext(source).orElseThrow();
            store.persist(source,OccupancyRules.decide(p.state(),p.event()));
            throw new IllegalStateException("crash before commit");
        }));
        assertEquals(0,number("select count(*) from billing.occupancy_interval where source='"+source+"'"));
        assertEquals(1,number("select last_applied_sequence from billing.occupancy_stream where source='"+source+"'"));
        assertInstanceOf(Decision.Apply.class,apply.applyNext(source));
    }
}
