package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache.LocalSnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresSnapshotStore;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.QueryService;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.SnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.SnapshotStore;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class QueryTest extends StoreFixture {
  @Test
  void cacheFailureFallsBackAndDatabaseFailureIsNeverEmptyHistory() {
    var brokenCache =
        new SnapshotCache() {
          public java.util.Optional<HistoryReader.Snapshot> get(Key k) {
            throw new IllegalStateException();
          }

          public void put(Key k, HistoryReader.Snapshot s) {
            throw new IllegalStateException();
          }
        };
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    apply.applyNext(source);
    receive(event(source, 2, Event.Kind.CONFIRMED, null, null), 2);
    apply.applyNext(source);
    var q = new HistoryReader.Query(source, t, t.plusSeconds(120));
    assertInstanceOf(
        HistoryReader.Confirmed.class,
        new QueryService(tx, new PostgresSnapshotStore(tx), brokenCache).lookup(q));
    var failingStore =
        new SnapshotStore() {
          public java.util.Optional<Head> head(HistoryReader.Query ignored) {
            throw new IllegalStateException("DB offline");
          }

          public java.util.List<HistoryReader.Slice> slices(HistoryReader.Query ignored) {
            return java.util.List.of();
          }
        };
    assertThrows(
        HistoryReader.HistoryUnavailable.class,
        () -> new QueryService(tx, failingStore, brokenCache).lookup(q));
  }

  @Test
  void queryKeepsOneSnapshotWhileWriterCommits() throws Exception {
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    apply.applyNext(source);
    receive(event(source, 2, Event.Kind.CONFIRMED, null, null), 2);
    apply.applyNext(source);
    var readHead = new CountDownLatch(1);
    var allowSlices = new CountDownLatch(1);
    var delegate = new PostgresSnapshotStore(tx);
    var barrierStore =
        new SnapshotStore() {
          public java.util.Optional<Head> head(HistoryReader.Query q) {
            var h = delegate.head(q);
            readHead.countDown();
            try {
              assertTrue(allowSlices.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            return h;
          }

          public java.util.List<HistoryReader.Slice> slices(HistoryReader.Query q) {
            return delegate.slices(q);
          }
        };
    var range = new HistoryReader.Query(source, t, t.plusSeconds(120));
    try (var pool = Executors.newSingleThreadExecutor()) {
      var result =
          pool.submit(
              () -> new QueryService(tx, barrierStore, new LocalSnapshotCache(10)).lookup(range));
      assertTrue(readHead.await(10, TimeUnit.SECONDS));
      try {
        receive(event(source, 3, Event.Kind.CONFIRMED, null, null), 3);
        apply.applyNext(source);
      } finally {
        allowSlices.countDown();
      }
      assertEquals(
          2, ((HistoryReader.Confirmed) result.get(10, TimeUnit.SECONDS)).snapshot().version());
      assertEquals(
          3,
          ((HistoryReader.Confirmed)
                  new QueryService(tx, delegate, new LocalSnapshotCache(10)).lookup(range))
              .snapshot()
              .version());
    }
  }

  @Test
  void confirmedBoundariesIdleAndOldCache() {
    var cache = new LocalSnapshotCache(2);
    var query = new QueryService(tx, new PostgresSnapshotStore(tx), cache);
    var range = new HistoryReader.Query(source, t, t.plusSeconds(240));
    assertEquals(
        new HistoryReader.NotReady(HistoryReader.Reason.UNREGISTERED), query.lookup(range));
    receive(event(source, 1, Event.Kind.INITIALIZED, null, null), 1);
    apply.applyNext(source);
    assertEquals(
        new HistoryReader.NotReady(HistoryReader.Reason.AWAITING_FACTS), query.lookup(range));
    var occ = UUID.randomUUID();
    receive(event(source, 2, Event.Kind.STARTED, occ, "x"), 2);
    apply.applyNext(source);
    receive(event(source, 3, Event.Kind.ENDED, occ, null), 3);
    apply.applyNext(source);
    receive(event(source, 4, Event.Kind.CONFIRMED, null, null), 4);
    apply.applyNext(source);
    var first = ((HistoryReader.Confirmed) query.lookup(range)).snapshot();
    assertEquals(1, first.slices().size());
    assertEquals(t.plusSeconds(120), first.slices().getFirst().startedAt());
    assertEquals(t.plusSeconds(180), first.slices().getFirst().endedAt());
    assertEquals(first, ((HistoryReader.Confirmed) query.lookup(range)).snapshot());
    receive(event(source, 5, Event.Kind.CONFIRMED, null, null), 5);
    apply.applyNext(source);
    cache.put(new SnapshotCache.Key(source, first.version(), range.from(), range.to()), first);
    assertTrue(
        ((HistoryReader.Confirmed) query.lookup(range)).snapshot().version() > first.version());
    var idle =
        (HistoryReader.Confirmed)
            query.lookup(new HistoryReader.Query(source, t, t.plusSeconds(120)));
    assertTrue(idle.snapshot().slices().isEmpty());
    assertEquals(
        new HistoryReader.NotReady(HistoryReader.Reason.BEFORE_BASELINE),
        query.lookup(new HistoryReader.Query(source, t.minusMillis(1), t)));
  }
}
