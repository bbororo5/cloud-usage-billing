package io.github.bbororo5.cloudbilling.worker.occupancyhistory.application;

import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;

public final class ApplyService {
  private final TransactionRunner transactions;
  private final HistoryStore store;

  public ApplyService(TransactionRunner transactions, HistoryStore store) {
    this.transactions = transactions;
    this.store = store;
  }

  public Decision applyNext(String source) {
    return transactions.write(
        () ->
            store
                .lockAndLoadNext(source)
                .map(
                    p -> {
                      Decision decision = OccupancyRules.decide(p.state(), p.event());
                      return store.persist(source, decision);
                    })
                .orElse(null));
  }

  public int runDue(int limit) {
    var sources = transactions.read(() -> store.due(limit));
    for (String source : sources) applyNext(source); // one event per VM per round
    return sources.size();
  }
}
