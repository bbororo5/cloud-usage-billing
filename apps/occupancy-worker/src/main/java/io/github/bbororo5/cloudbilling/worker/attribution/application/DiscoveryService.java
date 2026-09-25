package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;

public final class DiscoveryService {
  private final TransactionRunner tx;
  private final WorkStore store;
  private final UsageLedger ledger;

  public DiscoveryService(TransactionRunner tx, WorkStore store, UsageLedger ledger) {
    this.tx = tx;
    this.store = store;
    this.ledger = ledger;
  }

  public record Progress(int read, int registered, boolean completed) {}

  public Progress runPage(int size) {
    if (size < 1 || size > 500) throw new IllegalArgumentException("Page size must be 1..500");
    var sweep = tx.read(store::sweep);
    if (sweep.upper() == null) {
      var upper = ledger.upperBound();
      if (upper.isEmpty()) return new Progress(0, 0, true);
      if (!tx.write(() -> store.beginSweep(sweep, upper.get()))) return new Progress(0, 0, false);
      return new Progress(0, 0, false);
    }
    var page = ledger.page(sweep.after(), sweep.upper(), size);
    int registered = tx.write(() -> store.register(sweep, page));
    return new Progress(page.size(), Math.max(0, registered), page.isEmpty() && registered >= 0);
  }
}
