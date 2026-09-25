package io.github.bbororo5.cloudbilling.worker.occupancyhistory.application;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;

public final class NotificationService {
  private final TransactionRunner tx;
  private final IssueStore store;
  private final AlertSender sender;

  public NotificationService(TransactionRunner tx, IssueStore store, AlertSender sender) {
    this.tx = tx;
    this.store = store;
    this.sender = sender;
  }

  public int deliverPending(int limit) {
    var pending = tx.write(() -> store.claimAlerts(limit));
    int delivered = 0;
    for (var alert : pending) {
      try {
        sender.send(alert);
      } catch (RuntimeException unavailable) {
        continue;
      }
      tx.write(
          () -> {
            store.delivered(alert.issueId());
            return null;
          });
      delivered++;
    }
    return delivered;
  }
}
