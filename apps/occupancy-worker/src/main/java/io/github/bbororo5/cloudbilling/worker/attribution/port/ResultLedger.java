package io.github.bbororo5.cloudbilling.worker.attribution.port;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import java.util.*;

public interface ResultLedger {
  void append(Prepared result);

  Optional<Prepared> read(Usage.Key event, UUID revision);

  final class RevisionConflict extends IllegalStateException {
    public RevisionConflict() {
      super("Conflicting revision payloads");
    }
  }
}
