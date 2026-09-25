package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionRetry;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;

public final class OperationsService implements AttributionRetry {
  private final TransactionRunner tx;
  private final OperationsStore store;
  private final AlertChannel channel;

  public OperationsService(TransactionRunner tx, OperationsStore store, AlertChannel channel) {
    this.tx = tx;
    this.store = store;
    this.channel = channel;
  }

  public Result retry(Command command, Operator operator) {
    if (operator == null
        || !operator.mayRetry()
        || operator.principal() == null
        || operator.principal().isBlank())
      throw new SecurityException("Operations authority required");
    return tx.write(() -> store.retry(command, operator));
  }

  public void notifyPending(int limit) {
    for (var alert : tx.read(() -> store.pending(limit))) {
      boolean sent;
      try {
        channel.send(alert);
        sent = true;
      } catch (RuntimeException e) {
        sent = false;
      }
      final boolean success = sent;
      tx.write(
          () -> {
            store.delivered(alert, success);
            return null;
          });
    }
  }
}
