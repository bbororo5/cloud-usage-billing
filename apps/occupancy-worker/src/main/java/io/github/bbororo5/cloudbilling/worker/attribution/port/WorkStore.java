package io.github.bbororo5.cloudbilling.worker.attribution.port;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import java.util.*;

/** Every method participates in the caller's transaction. */
public interface WorkStore {
  record Sweep(long generation, Usage.Key upper, Usage.Key after) {}

  record Claim(Usage usage, UUID token, Prepared prepared) {}

  Sweep sweep();

  boolean beginSweep(Sweep expected, Usage.Key upper);

  int register(Sweep expected, List<Usage> page);

  Optional<Claim> claim(int leaseSeconds);

  boolean owns(Claim claim);

  boolean prepare(Claim claim, Prepared result);

  boolean defer(Claim claim, AttributionRules.Deferred outcome);

  boolean restart(Claim claim);

  /** Persistence only: the approval service must verify storage and history under the VM lock. */
  boolean recordApproval(Claim claim, Prepared result);

  Optional<Prepared> approved(Usage.Key key);

  boolean monthClosed(Prepared result);
}
