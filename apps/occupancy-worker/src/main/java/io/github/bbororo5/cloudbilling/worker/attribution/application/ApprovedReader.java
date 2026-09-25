package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionReader;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryGuard;

public final class ApprovedReader implements AttributionReader {
  private final TransactionRunner tx;
  private final WorkStore store;
  private final HistoryGuard guard;
  private final ResultLedger ledger;

  public ApprovedReader(
      TransactionRunner tx, WorkStore store, HistoryGuard guard, ResultLedger ledger) {
    this.tx = tx;
    this.store = store;
    this.guard = guard;
    this.ledger = ledger;
  }

  public Result lookup(Query query) {
    if (query == null) throw new IllegalArgumentException("Missing query");
    try {
      var key = new Usage.Key(query.source(), query.eventId());
      var candidate = tx.read(() -> store.approved(key));
      if (candidate.isEmpty() || !candidate.get().account().equals(query.account()))
        return new Withheld();
      var p = candidate.get();
      return guard.locked(
          HistoryEvidence.query(p.usage()),
          h -> {
            var current = store.approved(key);
            if (current.isEmpty()
                || !current.get().equals(p)
                || !HistoryEvidence.sameOwner(p, HistoryEvidence.decide(p.usage(), h)))
              return new Withheld();
            var stored =
                ledger
                    .read(key, p.revision())
                    .orElseThrow(() -> new IllegalStateException("Approved revision missing"));
            if (!stored.equals(p)) throw new IllegalStateException("Approved revision differs");
            return new Ready(
                p.revision(),
                p.account(),
                p.occupancy(),
                p.usage().from(),
                p.usage().to(),
                p.usage().measurements().stream()
                    .map(m -> new Measurement(m.meter(), m.quantity(), m.unit()))
                    .toList());
          });
    } catch (RuntimeException e) {
      throw new AttributionUnavailable(e);
    }
  }
}
