package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryGuard;

/** The only business entry to recording approval. Prepared is data, not proof of verification. */
final class ApprovalService {
  private final TransactionRunner tx;
  private final WorkStore store;
  private final HistoryGuard guard;
  private final ResultLedger ledger;

  ApprovalService(TransactionRunner tx, WorkStore store, HistoryGuard guard, ResultLedger ledger) {
    this.tx = tx;
    this.store = store;
    this.guard = guard;
    this.ledger = ledger;
  }

  void complete(WorkStore.Claim claim, Prepared result) {
    try {
      var existing = ledger.read(result.usage().key(), result.revision());
      if (existing.isEmpty()) ledger.append(result);
      var verified = ledger.read(result.usage().key(), result.revision());
      if (verified.isEmpty()) throw new IllegalStateException("Revision not visible yet");
      if (!verified.get().equals(result)) throw new ResultLedger.RevisionConflict();
    } catch (ResultLedger.RevisionConflict e) {
      tx.write(() -> store.defer(claim, new AttributionRules.Failed("RESULT_CONFLICT")));
      return;
    }
    guard.locked(
        HistoryEvidence.query(result.usage()),
        history -> {
          if (!store.owns(claim)) return false;
          var decision = HistoryEvidence.decide(result.usage(), history);
          if (decision instanceof AttributionRules.Assigned assigned
              && HistoryEvidence.sameOwner(result, assigned)
              && assigned.historyVersion() == result.historyVersion()) {
            if (store.monthClosed(result))
              return store.defer(claim, new AttributionRules.Failed("MONTH_FINALIZED"));
            return store.recordApproval(claim, result);
          }
          if (decision instanceof AttributionRules.Assigned
              && !HistoryEvidence.sameOwner(result, decision))
            return store.defer(claim, new AttributionRules.Failed("OWNERSHIP_CHANGED"));
          if (decision instanceof AttributionRules.Failed failed) return store.defer(claim, failed);
          return store.restart(claim);
        });
  }
}
