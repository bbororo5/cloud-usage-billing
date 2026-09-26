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
  private final ApprovalService approval;

  public AttributionService(
      TransactionRunner tx,
      WorkStore store,
      HistoryReader history,
      HistoryGuard guard,
      ResultLedger ledger) {
    this.tx = tx;
    this.store = store;
    this.history = history;
    this.approval = new ApprovalService(tx, store, guard, ledger);
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
    approval.complete(claim, p);
    return true;
  }
}
