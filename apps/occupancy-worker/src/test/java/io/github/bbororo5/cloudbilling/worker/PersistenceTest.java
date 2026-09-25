package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class PersistenceTest extends StoreFixture {
  @Test
  void durableReplayGapAndIndependentVm() {
    var init = event(source, 1, Event.Kind.INITIALIZED, null, null);
    receive(init, 1);
    receive(init, 1);
    receive(init, 2);
    apply.applyNext(source);
    assertEquals(
        2, number("select count(*) from billing.occupancy_receipt where topic='" + source + "'"));
    assertEquals(
        1, number("select version from billing.occupancy_stream where source='" + source + "'"));
    var id = UUID.randomUUID();
    receive(event(source, 3, Event.Kind.ENDED, id, null), 3);
    assertInstanceOf(Decision.Wait.class, apply.applyNext(source));
    String other = source + "b";
    receive(event(other, 1, Event.Kind.INITIALIZED, null, null), 4);
    assertInstanceOf(Decision.Apply.class, apply.applyNext(other));
    receive(event(source, 2, Event.Kind.STARTED, id, "x"), 5);
    apply.applyNext(source);
    apply.applyNext(source);
    assertEquals(
        3,
        number(
            "select last_applied_sequence from billing.occupancy_stream where source='"
                + source
                + "'"));
    assertEquals(
        1,
        number(
            "select count(*) from billing.occupancy_interval where source='"
                + source
                + "' and ended_at is not null"));
  }

  @Test
  void unknownAccountAndConflictingIdentityArePreserved() {
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    apply.applyNext(source);
    var e = event(source, 2, Event.Kind.STARTED, UUID.randomUUID(), "missing");
    receive(e, 2);
    apply.applyNext(source);
    assertEquals(
        1,
        number(
            "select count(*) from billing.occupancy_issue where source='"
                + source
                + "' and status='OPEN'"));
    receive(
        new Event(
            e.source(),
            e.subject(),
            e.id(),
            e.kind(),
            e.sequence(),
            e.time(),
            e.occupancyId(),
            "x",
            null,
            null,
            null),
        3);
    assertEquals(
        3, number("select count(*) from billing.occupancy_receipt where topic='" + source + "'"));
    assertEquals(
        0, number("select count(*) from billing.occupancy_interval where source='" + source + "'"));
  }

  @Test
  void simultaneousApplyAndRollback() throws Exception {
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    var gate = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                gate.await();
                return apply.applyNext(source);
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return apply.applyNext(source);
              });
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    assertEquals(
        1, number("select version from billing.occupancy_stream where source='" + source + "'"));
    receive(event(source, 2, Event.Kind.STARTED, UUID.randomUUID(), "x"), 2);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.write(
                () -> {
                  var p = store.lockAndLoadNext(source).orElseThrow();
                  store.persist(source, OccupancyRules.decide(p.state(), p.event()));
                  throw new IllegalStateException("crash before commit");
                }));
    assertEquals(
        0, number("select count(*) from billing.occupancy_interval where source='" + source + "'"));
    assertEquals(
        1,
        number(
            "select last_applied_sequence from billing.occupancy_stream where source='"
                + source
                + "'"));
    assertInstanceOf(Decision.Apply.class, apply.applyNext(source));
  }

  @Test
  void databaseConstraintRejectionPreservesReceiptAndReportsFailure() {
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    apply.applyNext(source);
    var occ = UUID.randomUUID();
    receive(event(source, 2, Event.Kind.STARTED, occ, "x"), 2);
    apply.applyNext(source);
    receive(event(source, 3, Event.Kind.ENDED, occ, null), 3);
    apply.applyNext(source);
    var overlap =
        new Event(
            source,
            "instances/a",
            UUID.randomUUID(),
            Event.Kind.STARTED,
            4,
            t.plusSeconds(150),
            UUID.randomUUID(),
            "x",
            null,
            null,
            null);
    receive(overlap, 4);
    assertEquals(new Decision.Reject("STORAGE_CONSTRAINT"), apply.applyNext(source));
    assertEquals(
        1, number("select count(*) from billing.occupancy_interval where source='" + source + "'"));
    assertEquals(
        3,
        number(
            "select last_applied_sequence from billing.occupancy_stream where source='"
                + source
                + "'"));
    assertEquals(
        4, number("select count(*) from billing.occupancy_receipt where topic='" + source + "'"));
  }

  @Test
  void seededReorderingAndDuplicatesProduceSameHistory() {
    var occupancy = UUID.randomUUID();
    var events =
        List.of(
            event(source, 1, Event.Kind.INITIALIZED, null, null),
            event(source, 2, Event.Kind.STARTED, occupancy, "x"),
            event(source, 3, Event.Kind.ENDED, occupancy, null),
            event(source, 4, Event.Kind.CONFIRMED, null, null));
    var deliveries = new ArrayList<Event>();
    for (int i = 0; i < 4; i++) deliveries.addAll(events);
    Collections.shuffle(deliveries, new Random(20260926));
    int offset = 0;
    for (var e : deliveries) {
      receive(e, offset++);
      apply.applyNext(source);
    }
    for (int i = 0; i < 4; i++) apply.applyNext(source);
    assertEquals(
        4, number("select version from billing.occupancy_stream where source='" + source + "'"));
    assertEquals(
        4,
        number(
            "select count(*) from billing.occupancy_event where source='"
                + source
                + "' and state='APPLIED'"));
    assertEquals(
        1,
        number(
            "select count(*) from billing.occupancy_interval where source='"
                + source
                + "' and ended_at-started_at=interval '60 seconds'"));
    assertEquals(
        0, number("select count(*) from billing.occupancy_issue where source='" + source + "'"));
  }
}
