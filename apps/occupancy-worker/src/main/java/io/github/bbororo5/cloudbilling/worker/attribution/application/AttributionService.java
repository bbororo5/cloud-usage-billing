package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.*;
import java.util.*;

public final class AttributionService {
  private final TransactionRunner tx;
  private final WorkStore store;
  private final HistoryReader history;
  private final HistoryGuard guard;
  private final ResultLedger ledger;

  public AttributionService(
      TransactionRunner tx,
      WorkStore store,
      HistoryReader history,
      HistoryGuard guard,
      ResultLedger ledger) {
    this.tx = tx;
    this.store = store;
    this.history = history;
    this.guard = guard;
    this.ledger = ledger;
  }

  public boolean runOne() {
    var found = tx.write(() -> store.claim(30));
    if (found.isEmpty()) return false;
    var claim = found.get();
    var p = claim.prepared();
    if (p == null) {
      var decision =
          HistoryEvidence.decide(
              claim.usage(), history.lookup(HistoryEvidence.query(claim.usage())));
      if (decision instanceof AttributionRules.Waiting w) {
        tx.write(() -> store.defer(claim, w.reason(), false));
        return true;
      }
      if (decision instanceof AttributionRules.Failed f) {
        tx.write(() -> store.defer(claim, f.reason(), true));
        return true;
      }
      var a = (AttributionRules.Assigned) decision;
      p =
          new Prepared(
              UUID.randomUUID(), claim.usage(), a.account(), a.occupancy(), a.historyVersion());
      var prepared = p;
      if (!tx.write(() -> store.prepare(claim, prepared))) return true;
    }
    // Persisted payload is reused after response loss or restart. No VM lock crosses these writes.
    var result = p;
    try {
      var existing = ledger.read(result.usage().key(), result.revision());
      if (existing.isEmpty()) ledger.append(result);
      var verified = ledger.read(result.usage().key(), result.revision());
      if (verified.isEmpty()) throw new IllegalStateException("Revision not visible yet");
      if (!verified.get().equals(result)) throw new ResultLedger.RevisionConflict();
    } catch (ResultLedger.RevisionConflict e) {
      tx.write(() -> store.defer(claim, "RESULT_CONFLICT", true));
      return true;
    }
    guard.locked(
        HistoryEvidence.query(result.usage()),
        h -> {
          if (!store.owns(claim)) return false;
          var decision = HistoryEvidence.decide(result.usage(), h);
          if (decision instanceof AttributionRules.Assigned a
              && HistoryEvidence.sameOwner(result, a)
              && a.historyVersion() == result.historyVersion()) {
            if (store.monthClosed(result)) return store.defer(claim, "MONTH_FINALIZED", true);
            return store.approve(claim, result);
          }
          if (decision instanceof AttributionRules.Assigned
              && !HistoryEvidence.sameOwner(result, decision))
            return store.defer(claim, "OWNERSHIP_CHANGED", true);
          if (decision instanceof AttributionRules.Failed f)
            return store.defer(claim, f.reason(), true);
          return store.restart(claim);
        });
    return true;
  }
}
