package io.github.bbororo5.cloudbilling.worker.attribution.api;

import java.util.*;

/** Trusted operations adapter must supply authenticated authority; no HTTP endpoint is exposed. */
public interface AttributionRetry {
  Result retry(Command command, Operator operator);

  record Command(UUID requestId, String source, UUID eventId, String reason) {
    public Command {
      if (requestId == null
          || source == null
          || source.isBlank()
          || eventId == null
          || reason == null
          || reason.isBlank()) throw new IllegalArgumentException("Invalid retry command");
    }
  }

  record Operator(String principal, boolean mayRetry) {}

  enum Result {
    SCHEDULED,
    ALREADY_SCHEDULED,
    REJECTED
  }
}
