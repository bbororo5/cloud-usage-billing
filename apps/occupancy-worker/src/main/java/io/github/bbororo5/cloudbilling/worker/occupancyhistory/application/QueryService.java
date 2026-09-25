package io.github.bbororo5.cloudbilling.worker.occupancyhistory.application;

import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;

public final class QueryService implements HistoryReader {
  private final TransactionRunner tx;
  private final SnapshotStore store;
  private final SnapshotCache cache;

  public QueryService(TransactionRunner tx, SnapshotStore store, SnapshotCache cache) {
    this.tx = tx;
    this.store = store;
    this.cache = cache;
  }

  @Override
  public Result lookup(Query query) {
    if (query == null) throw new InvalidQuery();
    try {
      return tx.read(
          () -> {
            var found = store.head(query);
            if (found.isEmpty() || !found.get().initialized())
              return new NotReady(Reason.UNREGISTERED);
            var h = found.get();
            if (h.conflict() != null) return new Conflict(h.conflict());
            if (query.from().isBefore(h.baseline())) return new NotReady(Reason.BEFORE_BASELINE);
            if (query.to().isAfter(h.through())) return new NotReady(Reason.AWAITING_FACTS);
            var key = new SnapshotCache.Key(query.source(), h.version(), query.from(), query.to());
            try {
              var hit = cache.get(key);
              if (hit.isPresent()) return new Confirmed(hit.get());
            } catch (RuntimeException ignored) {
              /* Cache is optional; this DB snapshot remains authoritative. */
            }
            var snapshot =
                new Snapshot(
                    h.source(),
                    h.subject(),
                    h.baseline(),
                    h.through(),
                    h.version(),
                    store.slices(query));
            try {
              cache.put(key, snapshot);
            } catch (RuntimeException ignored) {
            }
            return new Confirmed(snapshot);
          });
    } catch (RuntimeException e) {
      throw new HistoryUnavailable(e);
    }
  }
}
