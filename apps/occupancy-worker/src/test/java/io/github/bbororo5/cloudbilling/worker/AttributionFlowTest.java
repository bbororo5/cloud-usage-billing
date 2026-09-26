package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.attribution.adapter.clickhouse.ClickHouseLedger;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres.PostgresOperations;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres.PostgresWorkStore;
import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionReader;
import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionRetry;
import io.github.bbororo5.cloudbilling.worker.attribution.application.*;
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
  @Test
  void approvalRejectsPayloadDifferentFromImmutablePreparation() {
    register(usage);
    var claim = atx.write(() -> work.claim(30)).orElseThrow();
    var prepared = new Prepared(UUID.randomUUID(), usage, "x", occupancy, 5);
    assertTrue(atx.write(() -> work.prepare(claim, prepared)));
    var altered = new Prepared(prepared.revision(), usage, "y", occupancy, 5);
    assertFalse(atx.write(() -> work.recordApproval(claim, altered)));
    assertTrue(atx.read(() -> work.approved(usage.key())).isEmpty());
    assertTrue(atx.write(() -> work.recordApproval(claim, prepared)));
    assertEquals(prepared, atx.read(() -> work.approved(usage.key())).orElseThrow());
  }

  JdbcTransactions atx;
  PostgresWorkStore work;
  ClickHouseLedger ledger, admin;
  PostgresHistoryGuard guard;
  QueryService history;
  AttributionService service;
  ApprovedReader reader;
  UUID occupancy = UUID.randomUUID();
  Usage usage;

  @BeforeEach
  void attributionSetup() throws Exception {
    owner(
        "truncate"
            + " billing.attribution_transition,billing.attribution_action,billing.attribution_issue,billing.attribution_approval,billing.attribution_attempt,billing.attribution_job");
    owner(
        "update billing.attribution_scan set"
            + " upper_source=null,upper_id=null,cursor_source=null,cursor_id=null,generation=generation+1");
    atx =
        new JdbcTransactions(
            new DriverManagerDataSource(
                System.getenv("OCCUPANCY_TEST_URL"), "billing_attribution", "local-dev-only"));
    work = new PostgresWorkStore(atx);
    var endpoint = URI.create(System.getenv("ATTRIBUTION_TEST_CH"));
    ledger = new ClickHouseLedger(endpoint, "billing_attribution", "local-attribution-only");
    admin = new ClickHouseLedger(endpoint, "default", "");
    guard = new PostgresHistoryGuard(atx);
    history = new QueryService(tx, new PostgresSnapshotStore(tx), new LocalSnapshotCache(5));
    service = new AttributionService(atx, work, history, guard, ledger);
    reader = new ApprovedReader(atx, work, guard, ledger);
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 0);
    apply.runDue(10);
    receive(event(source, 2, Event.Kind.STARTED, occupancy, "x"), 1);
    apply.runDue(10);
    receive(event(source, 3, Event.Kind.ENDED, occupancy, null), 2);
    apply.runDue(10);
    owner(
        "insert into billing.billing_account(billing_account_id,billing_account_name)"
            + " values('y','Y') on conflict do nothing");
    receive(event(source, 4, Event.Kind.STARTED, UUID.randomUUID(), "y"), 3);
    apply.runDue(10);
    receive(event(source, 5, Event.Kind.CONFIRMED, null, null), 4);
    apply.runDue(10);
    usage =
        new Usage(
            new Usage.Key(source, UUID.randomUUID()),
            "instances/a",
            t.plusSeconds(120),
            t.plusSeconds(180),
            "r",
            "vm",
            "VirtualMachine",
            new AttributionRulesTest().usage().measurements());
  }

  void owner(String query) throws Exception {
    try (var c =
            DriverManager.getConnection(
                System.getenv("OCCUPANCY_TEST_URL"), "billing_owner", "local-dev-only");
        var s = c.createStatement()) {
      s.execute(query);
    }
  }

  void register(Usage u) {
    var s = atx.read(work::sweep);
    assertTrue(atx.write(() -> work.beginSweep(s, u.key())));
    var started = atx.read(work::sweep);
    atx.write(() -> work.register(started, List.of(u)));
    var end = atx.read(work::sweep);
    atx.write(() -> work.register(end, List.of()));
  }

  AttributionReader.Result read(String account) {
    return reader.lookup(new AttributionReader.Query(account, source, usage.key().id()));
  }

  void expire() throws Exception {
    owner(
        "update billing.attribution_job set lease_until=clock_timestamp()-interval '1"
            + " second',due_at=clock_timestamp() where token is not null");
  }

  @Test
  void lateUsageApprovedForHistoricalOccupancyAndOtherCompanyWithheld() {
    register(usage);
    assertTrue(service.runOne());
    var result = assertInstanceOf(AttributionReader.Ready.class, read("x"));
    assertEquals(occupancy, result.occupancy());
    assertEquals(
        usage.measurements().getFirst().quantity(), result.measurements().getFirst().quantity());
    assertInstanceOf(AttributionReader.Withheld.class, read("y"));
    assertFalse(service.runOne());
  }

  @Test
  void crashAfterClickHouseWriteRecoversSameRevision() throws Exception {
    register(usage);
    ResultLedger lostResponse =
        new ResultLedger() {
          public Optional<Prepared> read(Usage.Key key, UUID revision) {
            return ledger.read(key, revision);
          }

          public void append(Prepared p) {
            ledger.append(p);
            throw new IllegalStateException("response lost; process ends");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> new AttributionService(atx, work, history, guard, lostResponse).runOne());
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
    expire();
    var recovered = new AttributionService(atx, new PostgresWorkStore(atx), history, guard, ledger);
    assertTrue(recovered.runOne());
    var p = atx.read(() -> work.approved(usage.key())).orElseThrow();
    ledger.append(p);
    ledger.append(p);
    assertEquals(p, ledger.read(usage.key(), p.revision()).orElseThrow());
    assertEquals(
        p.revision(), assertInstanceOf(AttributionReader.Ready.class, read("x")).revision());
  }

  @Test
  void expiredExecutorCannotPrepareOrApprove() throws Exception {
    register(usage);
    var old = atx.write(() -> work.claim(30)).orElseThrow();
    expire();
    var newer = atx.write(() -> work.claim(30)).orElseThrow();
    var p = new Prepared(UUID.randomUUID(), usage, "x", occupancy, 5);
    assertFalse(atx.write(() -> work.prepare(old, p)));
    assertFalse(atx.write(() -> work.recordApproval(old, p)));
    assertFalse(atx.write(() -> work.defer(old, new AttributionRules.Failed("HISTORY_CONFLICT"))));
    assertTrue(atx.write(() -> work.prepare(newer, p)));
  }

  @Test
  void approvalRechecksVersionAndLeavesOldRevisionUnpublished() throws Exception {
    register(usage);
    ResultLedger changeAfterSave =
        new ResultLedger() {
          public Optional<Prepared> read(Usage.Key key, UUID revision) {
            return ledger.read(key, revision);
          }

          public void append(Prepared p) {
            ledger.append(p);
            receive(event(source, 6, Event.Kind.CONFIRMED, null, null), 5);
            apply.runDue(10);
          }
        };
    new AttributionService(atx, work, history, guard, changeAfterSave).runOne();
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
    assertTrue(service.runOne());
    assertInstanceOf(AttributionReader.Ready.class, read("x"));
  }

  @Test
  void laterHistoryErrorBlocksApprovedRead() {
    register(usage);
    service.runOne();
    assertInstanceOf(AttributionReader.Ready.class, read("x"));
    // Same sequence, different content creates a durable history issue under the VM lock.
    receive(event(source, 3, Event.Kind.ENDED, UUID.randomUUID(), null), 8);
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
  }

  @Test
  void snapshotLockIsHeldUntilResultHasBeenMaterialized() throws Exception {
    register(usage);
    service.runOne();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ResultLedger paused =
        new ResultLedger() {
          public void append(Prepared p) {
            ledger.append(p);
          }

          public Optional<Prepared> read(Usage.Key key, UUID revision) {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return ledger.read(key, revision);
          }
        };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future =
          executor.submit(
              () ->
                  new ApprovedReader(atx, work, guard, paused)
                      .lookup(new AttributionReader.Query("x", source, usage.key().id())));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try (var c =
              DriverManager.getConnection(
                  System.getenv("OCCUPANCY_TEST_URL"), "billing_owner", "local-dev-only");
          var s = c.createStatement()) {
        assertThrows(
            SQLException.class,
            () ->
                s.execute(
                    "select * from billing.occupancy_stream where source='"
                        + source
                        + "' for update nowait"));
      } finally {
        release.countDown();
      }
      assertInstanceOf(AttributionReader.Ready.class, future.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void customerClickHouseIdentitiesHaveNoPreparedReadAccess() {
    var bff =
        new ClickHouseLedger(
            URI.create(System.getenv("ATTRIBUTION_TEST_CH")), "billing_bff", "local-bff-only");
    assertThrows(
        IllegalStateException.class,
        () -> bff.execute("select * from billing.attribution_revision", Map.of()));
  }

  @Test
  void discoveryReadsRealUsageAndNextSweepFindsLateInput() {
    // Key prefix sorts before the already persisted scan cursor, so only another sweep can find it.
    raw(usage);
    var discovery = new DiscoveryService(atx, work, ledger);
    discovery.runPage(500); // Freeze this sweep's upper bound.
    discovery.runPage(500); // Cursor has passed the late event's future position.
    var late =
        new Usage(
            new Usage.Key("aaa-late-" + source, UUID.randomUUID()),
            usage.subject(),
            usage.from(),
            usage.to(),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    raw(late);
    int currentAdded = 0;
    for (int i = 0; i < 20; i++) {
      var p = discovery.runPage(500);
      currentAdded += p.registered();
      if (p.completed()) break;
    }
    assertEquals(0, currentAdded);
    int added = 0;
    for (int i = 0; i < 20; i++) {
      var progress = discovery.runPage(500);
      added += progress.registered();
      if (progress.completed()) break;
    }
    assertEquals(1, added);
  }

  void raw(Usage u) {
    var n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    n.put("event_source", u.key().source())
        .put("event_id", u.key().id().toString())
        .put("event_subject", u.subject())
        .put("event_time", u.to().toString())
        .put("charge_period_start", u.from().toString())
        .put("charge_period_end", u.to().toString())
        .put("region_id", u.region())
        .put("resource_id", u.resource())
        .put("resource_type", u.resourceType())
        .put("kafka_topic", "test")
        .put("kafka_partition", 0)
        .put("kafka_offset", 1);
    var values = n.putArray("measurements");
    for (var m : u.measurements())
      values
          .addObject()
          .put("meter", m.meter())
          .put("quantity", m.quantity().toString())
          .put("unit", m.unit());
    admin.execute(
        "insert into billing.usage_record_delivery FORMAT JSONEachRow\n" + n + "\n", Map.of());
  }

  @Test
  void retryRequiresAuthorityIsIdempotentAndDoesNotResolveErrorPrematurely() throws Exception {
    // Confirmed idle interval: retry cannot invent a payer.
    var idle =
        new Usage(
            usage.key(),
            usage.subject(),
            t,
            t.plusSeconds(60),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    register(idle);
    service.runOne();
    var operations = new OperationsService(atx, new PostgresOperations(atx), a -> {});
    var command =
        new AttributionRetry.Command(
            UUID.randomUUID(), source, usage.key().id(), "checked infrastructure");
    var operator = new AttributionRetry.Operator("operator", true);
    assertThrows(
        SecurityException.class,
        () -> operations.retry(command, new AttributionRetry.Operator("viewer", false)));
    assertEquals(AttributionRetry.Result.SCHEDULED, operations.retry(command, operator));
    assertEquals(AttributionRetry.Result.ALREADY_SCHEDULED, operations.retry(command, operator));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            operations.retry(
                new AttributionRetry.Command(
                    command.requestId(), source, UUID.randomUUID(), command.reason()),
                operator));
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
    assertEquals(1, atx.read(() -> new PostgresOperations(atx).pending(50)).size());
    assertTrue(service.runOne());
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
  }

  @Test
  void alertFailureIsDurableAndCanBeRedelivered() throws Exception {
    var idle =
        new Usage(
            usage.key(),
            usage.subject(),
            t,
            t.plusSeconds(60),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    register(idle);
    service.runOne();
    var operations = new PostgresOperations(atx);
    new OperationsService(
            atx,
            operations,
            a -> {
              throw new IllegalStateException("channel down");
            })
        .notifyPending(50);
    owner("update billing.attribution_issue set next_alert_at=clock_timestamp()");
    var delivered = new ArrayList<OperationsStore.Alert>();
    new OperationsService(atx, operations, delivered::add).notifyPending(50);
    assertEquals(1, delivered.size());
    assertEquals(1, delivered.getFirst().attempt());
    assertTrue(atx.read(() -> operations.pending(50)).isEmpty());
  }

  @Test
  void waitingVmDoesNotStopAnotherAndCanRecoverWhenFactsArrive() throws Exception {
    var later =
        new Usage(
            new Usage.Key(source, UUID.randomUUID()),
            usage.subject(),
            t.plusSeconds(300),
            t.plusSeconds(360),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    register(later);
    service.runOne();
    String other = source + ":other";
    var secondOccupancy = UUID.randomUUID();
    receive(event(other, 1, Event.Kind.INITIALIZED, null, null), 20);
    apply.runDue(10);
    receive(event(other, 2, Event.Kind.STARTED, secondOccupancy, "x"), 21);
    apply.runDue(10);
    receive(event(other, 3, Event.Kind.CONFIRMED, null, null), 22);
    apply.runDue(10);
    var otherUsage =
        new Usage(
            new Usage.Key(other, UUID.randomUUID()),
            usage.subject(),
            usage.from(),
            usage.to(),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    register(otherUsage);
    for (int i = 0; i < 3; i++) service.runOne();
    assertInstanceOf(
        AttributionReader.Ready.class,
        reader.lookup(new AttributionReader.Query("x", other, otherUsage.key().id())));
    receive(event(source, 6, Event.Kind.CONFIRMED, null, null), 6);
    apply.runDue(10);
    owner("update billing.attribution_job set due_at=clock_timestamp() where state='WAITING'");
    service.runOne();
    assertInstanceOf(
        AttributionReader.Ready.class,
        reader.lookup(new AttributionReader.Query("y", source, later.key().id())));
  }

  @Test
  void registeringUnknownCompanyAndAuthorizedRetriesResolveOnlyThatWork() throws Exception {
    String unknown = source + ":unknown", company = "new-" + UUID.randomUUID();
    UUID newOccupancy = UUID.randomUUID();
    receive(event(unknown, 1, Event.Kind.INITIALIZED, null, null), 40);
    apply.runDue(10);
    receive(event(unknown, 2, Event.Kind.STARTED, newOccupancy, company), 41);
    apply.runDue(10);
    receive(event(unknown, 3, Event.Kind.CONFIRMED, null, null), 42);
    apply.runDue(10);
    var u =
        new Usage(
            new Usage.Key(unknown, UUID.randomUUID()),
            usage.subject(),
            usage.from(),
            usage.to(),
            usage.region(),
            usage.resource(),
            usage.resourceType(),
            usage.measurements());
    register(u);
    service.runOne();
    var blocked = new AttributionReader.Query(company, unknown, u.key().id());
    assertInstanceOf(AttributionReader.Withheld.class, reader.lookup(blocked));
    register(usage);
    service.runOne();
    assertInstanceOf(AttributionReader.Ready.class, read("x"));
    owner(
        "insert into billing.billing_account(billing_account_id,billing_account_name) values('"
            + company
            + "','New company')");
    UUID issue =
        tx.read(
            () -> {
              try (var s =
                  tx.connection()
                      .prepareStatement(
                          "select issue_id from billing.occupancy_issue where source=? and"
                              + " reason='UNKNOWN_ACCOUNT'")) {
                s.setString(1, unknown);
                try (var r = s.executeQuery()) {
                  assertTrue(r.next());
                  return r.getObject(1, UUID.class);
                }
              } catch (SQLException e) {
                throw new RuntimeException(e);
              }
            });
    var historyRetry =
        new io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.RetryService(
            tx, new PostgresIssueStore(tx));
    assertInstanceOf(
        io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry.Scheduled.class,
        historyRetry.retry(
            new io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry.Command(
                UUID.randomUUID(), issue, "company registered"),
            new io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry
                .OperatorContext("operator", true)));
    apply.runDue(10);
    apply.runDue(10);
    var ops = new OperationsService(atx, new PostgresOperations(atx), a -> {});
    assertEquals(
        AttributionRetry.Result.SCHEDULED,
        ops.retry(
            new AttributionRetry.Command(
                UUID.randomUUID(), unknown, u.key().id(), "history recovered"),
            new AttributionRetry.Operator("operator", true)));
    assertInstanceOf(AttributionReader.Withheld.class, reader.lookup(blocked));
    service.runOne();
    assertInstanceOf(AttributionReader.Ready.class, reader.lookup(blocked));
    assertInstanceOf(AttributionReader.Ready.class, read("x"));
  }

  @Test
  void ledgerFailureIsUnavailableNotEmpty() {
    register(usage);
    service.runOne();
    ResultLedger failed =
        new ResultLedger() {
          public void append(Prepared p) {
            throw new IllegalStateException("unavailable");
          }

          public Optional<Prepared> read(Usage.Key key, UUID revision) {
            throw new IllegalStateException("unavailable");
          }
        };
    assertThrows(
        AttributionReader.AttributionUnavailable.class,
        () ->
            new ApprovedReader(atx, work, guard, failed)
                .lookup(new AttributionReader.Query("x", source, usage.key().id())));
  }

  @Test
  void sameRevisionWithConflictingContentsIsNeverSelected() {
    var p = new Prepared(UUID.randomUUID(), usage, "x", occupancy, 5);
    ledger.append(p);
    ledger.append(new Prepared(p.revision(), usage, "y", occupancy, 5));
    assertThrows(IllegalStateException.class, () -> ledger.read(usage.key(), p.revision()));
  }

  @Test
  void conflictingStorageOpensIssueInsteadOfRetryingSilently() {
    register(usage);
    ResultLedger conflicting =
        new ResultLedger() {
          public Optional<Prepared> read(Usage.Key key, UUID revision) {
            return ledger.read(key, revision);
          }

          public void append(Prepared p) {
            ledger.append(p);
            ledger.append(
                new Prepared(p.revision(), p.usage(), "y", p.occupancy(), p.historyVersion()));
          }
        };
    assertDoesNotThrow(
        () -> new AttributionService(atx, work, history, guard, conflicting).runOne());
    assertEquals(
        "RESULT_CONFLICT",
        atx.read(() -> new PostgresOperations(atx).pending(50)).getFirst().reason());
    assertInstanceOf(AttributionReader.Withheld.class, read("x"));
  }

  @Test
  void overlappingExecutorsAcquireExactlyOneClaim() throws Exception {
    register(usage);
    var start = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> attempt =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            return atx.write(() -> work.claim(30)).isPresent();
          };
      var a = executor.submit(attempt);
      var b = executor.submit(attempt);
      assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void existingPreparedPayloadCannotBeReplacedEvenBySameLease() {
    register(usage);
    var claim = atx.write(() -> work.claim(30)).orElseThrow();
    var first = new Prepared(UUID.randomUUID(), usage, "x", occupancy, 5);
    assertTrue(atx.write(() -> work.prepare(claim, first)));
    assertFalse(
        atx.write(
            () -> work.prepare(claim, new Prepared(UUID.randomUUID(), usage, "y", occupancy, 5))));
  }

  @Test
  void finalizedMonthIsHeldWithoutChangingSettlement() throws Exception {
    owner(
        "insert into billing.settlement_job(billing_account_id,billing_month,status,finalized_at)"
            + " values('x','2026-09-01','FINALIZED',now())");
    try {
      register(usage);
      service.runOne();
      assertInstanceOf(AttributionReader.Withheld.class, read("x"));
      var alerts = atx.read(() -> new PostgresOperations(atx).pending(50));
      assertEquals("MONTH_FINALIZED", alerts.getFirst().reason());
    } finally {
      owner(
          "delete from billing.settlement_job where billing_account_id='x' and"
              + " billing_month='2026-09-01'");
    }
  }

  @Test
  void processTerminationAfterStoredRevisionRecoversOnRestart() throws Exception {
    register(usage);
    var log = java.nio.file.Files.createTempFile("attribution-process-", ".log");
    Process child = null;
    try {
      try (var connection =
              DriverManager.getConnection(
                  System.getenv("OCCUPANCY_TEST_URL"), "billing_owner", "local-dev-only");
          var statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute(
            "select * from billing.occupancy_stream where source='" + source + "' for update");
        child = startWorker(log);
        await(
            () -> {
              var revisions = preparedRevision();
              return revisions.isPresent() && ledger.read(usage.key(), revisions.get()).isPresent();
            });
        assertTrue(child.isAlive());
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        child = null;
        connection.rollback();
      }
      UUID saved = preparedRevision().orElseThrow();
      expire();
      child = startWorker(log);
      await(() -> atx.read(() -> work.approved(usage.key())).isPresent());
      assertEquals(saved, assertInstanceOf(AttributionReader.Ready.class, read("x")).revision());
    } catch (Throwable e) {
      throw new AssertionError("Worker log: " + java.nio.file.Files.readString(log), e);
    } finally {
      if (child != null) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
      java.nio.file.Files.deleteIfExists(log);
    }
  }

  Optional<UUID> preparedRevision() {
    return atx.read(
        () -> {
          try (var s =
              atx.connection()
                  .prepareStatement(
                      "select prepared_revision from billing.attribution_job where source=? and"
                          + " event_id=?")) {
            s.setString(1, source);
            s.setObject(2, usage.key().id());
            try (var r = s.executeQuery()) {
              return r.next() ? Optional.ofNullable(r.getObject(1, UUID.class)) : Optional.empty();
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  Process startWorker(java.nio.file.Path log) throws Exception {
    var builder =
        new ProcessBuilder(
            java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar",
            System.getProperty("worker.jar"),
            "--occupancy.enabled=false",
            "--attribution.enabled=true");
    var env = builder.environment();
    env.put("OCCUPANCY_DB_URL", System.getenv("OCCUPANCY_TEST_URL"));
    env.put("OCCUPANCY_DB_PASSWORD", "local-dev-only");
    env.put("ATTRIBUTION_DB_URL", System.getenv("OCCUPANCY_TEST_URL"));
    env.put("ATTRIBUTION_DB_PASSWORD", "local-dev-only");
    env.put("ATTRIBUTION_CLICKHOUSE_URL", System.getenv("ATTRIBUTION_TEST_CH"));
    env.put("ATTRIBUTION_CLICKHOUSE_PASSWORD", "local-attribution-only");
    return builder
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .start();
  }

  static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(50);
    }
    fail("Condition did not become true before deadline");
  }
}
