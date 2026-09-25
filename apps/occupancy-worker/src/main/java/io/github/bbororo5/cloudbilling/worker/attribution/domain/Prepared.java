package io.github.bbororo5.cloudbilling.worker.attribution.domain;

import java.util.*;

public record Prepared(
    UUID revision, Usage usage, String account, UUID occupancy, long historyVersion) {
  public Prepared {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(usage);
    Objects.requireNonNull(occupancy);
    if (account == null || account.isBlank() || historyVersion < 0)
      throw new IllegalArgumentException("Invalid prepared result");
  }
}
