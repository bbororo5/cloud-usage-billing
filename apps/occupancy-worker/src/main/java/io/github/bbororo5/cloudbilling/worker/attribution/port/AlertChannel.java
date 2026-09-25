package io.github.bbororo5.cloudbilling.worker.attribution.port;

@FunctionalInterface
public interface AlertChannel {
  void send(OperationsStore.Alert alert);
}
