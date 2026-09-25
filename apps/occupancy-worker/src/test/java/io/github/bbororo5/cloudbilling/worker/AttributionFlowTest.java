package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.attribution.adapter.clickhouse.ClickHouseLedger;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres.PostgresWorkStore;
import io.github.bbororo5.cloudbilling.worker.attribution.application.*;
import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionReader;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache.LocalSnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.QueryService;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("attribution")
class AttributionFlowTest extends StoreFixture {
  JdbcTransactions atx; PostgresWorkStore work; ClickHouseLedger ledger,admin;
  PostgresHistoryGuard guard; QueryService history; AttributionService service; ApprovedReader reader;
  UUID occupancy=UUID.randomUUID(); Usage usage;
  @BeforeEach void attributionSetup() throws Exception {
    owner("truncate billing.attribution_action,billing.attribution_issue,billing.attribution_approval,billing.attribution_attempt,billing.attribution_job");
    owner("update billing.attribution_scan set upper_source=null,upper_id=null,cursor_source=null,cursor_id=null,generation=generation+1");
    atx=new JdbcTransactions(new DriverManagerDataSource(System.getenv("OCCUPANCY_TEST_URL"),"billing_attribution","local-dev-only"));
    work=new PostgresWorkStore(atx);
    var endpoint=URI.create(System.getenv("ATTRIBUTION_TEST_CH"));
    ledger=new ClickHouseLedger(endpoint,"billing_attribution","local-attribution-only");
    admin=new ClickHouseLedger(endpoint,"default","");
    guard=new PostgresHistoryGuard(atx);
    history=new QueryService(tx,new PostgresSnapshotStore(tx),new LocalSnapshotCache(5));
    service=new AttributionService(atx,work,history,guard,ledger);
    reader=new ApprovedReader(atx,work,guard,ledger);
    receive(event(source,1,Event.Kind.INITIALIZED,null,null),0); apply.runDue(10);
    receive(event(source,2,Event.Kind.STARTED,occupancy,"x"),1); apply.runDue(10);
    receive(event(source,3,Event.Kind.ENDED,occupancy,null),2); apply.runDue(10);
    owner("insert into billing.billing_account(billing_account_id,billing_account_name) values('y','Y') on conflict do nothing");
    receive(event(source,4,Event.Kind.STARTED,UUID.randomUUID(),"y"),3); apply.runDue(10);
    receive(event(source,5,Event.Kind.CONFIRMED,null,null),4); apply.runDue(10);
    usage=new Usage(new Usage.Key(source,UUID.randomUUID()),"instances/a",t.plusSeconds(120),t.plusSeconds(180),"r","vm","VirtualMachine",new AttributionRulesTest().usage().measurements());
  }
  void owner(String query) throws Exception {
    try(var c=DriverManager.getConnection(System.getenv("OCCUPANCY_TEST_URL"),"billing_owner","local-dev-only");var s=c.createStatement()) { s.execute(query); }
  }
  void register(Usage u) {
    var s=atx.read(work::sweep); assertTrue(atx.write(() -> work.beginSweep(s,u.key())));
    var started=atx.read(work::sweep); atx.write(() -> work.register(started,List.of(u)));
    var end=atx.read(work::sweep); atx.write(() -> work.register(end,List.of()));
  }
  AttributionReader.Result read(String account) { return reader.lookup(new AttributionReader.Query(account,source,usage.key().id())); }
  void expire() throws Exception { owner("update billing.attribution_job set lease_until=clock_timestamp()-interval '1 second',due_at=clock_timestamp() where token is not null"); }
  @Test void lateUsageApprovedForHistoricalOccupancyAndOtherCompanyWithheld() {
    register(usage); assertTrue(service.runOne());
    var result=assertInstanceOf(AttributionReader.Ready.class,read("x"));
    assertEquals(occupancy,result.occupancy()); assertEquals(usage.measurements().getFirst().quantity(),result.measurements().getFirst().quantity());
    assertInstanceOf(AttributionReader.Withheld.class,read("y"));
    assertFalse(service.runOne());
  }
  @Test void crashAfterClickHouseWriteRecoversSameRevision() throws Exception {
    register(usage);
    ResultLedger lostResponse=new ResultLedger() {
      public Optional<Prepared> read(Usage.Key key,UUID revision) { return ledger.read(key,revision); }
      public void append(Prepared p) { ledger.append(p); throw new IllegalStateException("response lost; process ends"); }
    };
    assertThrows(IllegalStateException.class,() -> new AttributionService(atx,work,history,guard,lostResponse).runOne());
    assertInstanceOf(AttributionReader.Withheld.class,read("x"));
    expire(); var recovered=new AttributionService(atx,new PostgresWorkStore(atx),history,guard,ledger);
    assertTrue(recovered.runOne());
    var p=atx.read(() -> work.approved(usage.key())).orElseThrow();
    ledger.append(p); ledger.append(p);
    assertEquals(p,ledger.read(usage.key(),p.revision()).orElseThrow());
    assertEquals(p.revision(),assertInstanceOf(AttributionReader.Ready.class,read("x")).revision());
  }
  @Test void expiredExecutorCannotPrepareOrApprove() throws Exception {
    register(usage); var old=atx.write(() -> work.claim(30)).orElseThrow(); expire();
    var newer=atx.write(() -> work.claim(30)).orElseThrow();
    var p=new Prepared(UUID.randomUUID(),usage,"x",occupancy,5);
    assertFalse(atx.write(() -> work.prepare(old,p)));
    assertFalse(atx.write(() -> work.approve(old,p)));
    assertFalse(atx.write(() -> work.defer(old,"oops",true)));
    assertTrue(atx.write(() -> work.prepare(newer,p)));
  }
  @Test void approvalRechecksVersionAndLeavesOldRevisionUnpublished() throws Exception {
    register(usage);
    ResultLedger changeAfterSave=new ResultLedger() {
      public Optional<Prepared> read(Usage.Key key,UUID revision) { return ledger.read(key,revision); }
      public void append(Prepared p) {
        ledger.append(p);
        receive(event(source,6,Event.Kind.CONFIRMED,null,null),5); apply.runDue(10);
      }
    };
    new AttributionService(atx,work,history,guard,changeAfterSave).runOne();
    assertInstanceOf(AttributionReader.Withheld.class,read("x"));
    assertTrue(service.runOne()); assertInstanceOf(AttributionReader.Ready.class,read("x"));
  }
  @Test void laterHistoryErrorBlocksApprovedRead() {
    register(usage); service.runOne();
    assertInstanceOf(AttributionReader.Ready.class,read("x"));
    // Same sequence, different content creates a durable history issue under the VM lock.
    receive(event(source,3,Event.Kind.ENDED,UUID.randomUUID(),null),8);
    assertInstanceOf(AttributionReader.Withheld.class,read("x"));
  }
  @Test void snapshotLockIsHeldUntilResultHasBeenMaterialized() throws Exception {
    register(usage); service.runOne(); var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    ResultLedger paused=new ResultLedger() {
      public void append(Prepared p) { ledger.append(p); }
      public Optional<Prepared> read(Usage.Key key,UUID revision) {
        entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException e) { throw new RuntimeException(e); }
        return ledger.read(key,revision);
      }
    };
    try(var executor=Executors.newSingleThreadExecutor()) {
      var future=executor.submit(() -> new ApprovedReader(atx,work,guard,paused).lookup(new AttributionReader.Query("x",source,usage.key().id())));
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      try(var c=DriverManager.getConnection(System.getenv("OCCUPANCY_TEST_URL"),"billing_owner","local-dev-only");var s=c.createStatement()) {
        assertThrows(SQLException.class,() -> s.execute("select * from billing.occupancy_stream where source='"+source+"' for update nowait"));
      } finally { release.countDown(); }
      assertInstanceOf(AttributionReader.Ready.class,future.get(5,TimeUnit.SECONDS));
    }
  }
  @Test void customerClickHouseIdentitiesHaveNoPreparedReadAccess() {
    var bff=new ClickHouseLedger(URI.create(System.getenv("ATTRIBUTION_TEST_CH")),"billing_bff","local-bff-only");
    assertThrows(IllegalStateException.class,() -> bff.execute("select * from billing.attribution_revision",Map.of()));
  }
  @Test void discoveryReadsRealUsageAndNextSweepFindsLateInput() {
    // Key prefix sorts before the already persisted scan cursor, so only another sweep can find it.
    raw(usage); var discovery=new DiscoveryService(atx,work,ledger);
    for(int i=0;i<20;i++) if(discovery.runPage(500).completed()) break;
    var late=new Usage(new Usage.Key("aaa-late-"+source,UUID.randomUUID()),usage.subject(),usage.from(),usage.to(),usage.region(),usage.resource(),usage.resourceType(),usage.measurements());
    raw(late);
    int added=0; for(int i=0;i<20;i++) {var progress=discovery.runPage(500);added+=progress.registered();if(progress.completed())break;}
    assertEquals(1,added);
  }
  void raw(Usage u) {
    var n=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    n.put("event_source",u.key().source()).put("event_id",u.key().id().toString()).put("event_subject",u.subject())
      .put("event_time",u.to().toString()).put("charge_period_start",u.from().toString()).put("charge_period_end",u.to().toString())
      .put("region_id",u.region()).put("resource_id",u.resource()).put("resource_type",u.resourceType())
      .put("kafka_topic","test").put("kafka_partition",0).put("kafka_offset",1);
    var values=n.putArray("measurements");for(var m:u.measurements()) values.addObject().put("meter",m.meter()).put("quantity",m.quantity().toString()).put("unit",m.unit());
    admin.execute("insert into billing.usage_record_delivery FORMAT JSONEachRow\n"+n+"\n",Map.of());
  }
}
