package io.github.bbororo5.cloudbilling.worker.attribution.port;

import io.github.bbororo5.cloudbilling.worker.attribution.api.AttributionRetry;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.Usage;
import java.util.*;

public interface OperationsStore {
  AttributionRetry.Result retry(
      AttributionRetry.Command command, AttributionRetry.Operator operator);

  List<Alert> pending(int limit);

  void delivered(Alert alert, boolean success);

  record Alert(
      Usage.Key key,
      java.time.Instant from,
      java.time.Instant to,
      String account,
      String reason,
      int attempt) {}
}
