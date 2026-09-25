package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;

@FunctionalInterface
public interface AlertSender {
  void send(IssueStore.Alert alert);
}
