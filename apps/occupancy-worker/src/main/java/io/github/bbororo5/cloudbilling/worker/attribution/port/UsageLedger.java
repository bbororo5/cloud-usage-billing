package io.github.bbororo5.cloudbilling.worker.attribution.port;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.Usage;
import java.util.*;

public interface UsageLedger {
  Optional<Usage.Key> upperBound();
  List<Usage> page(Usage.Key after, Usage.Key upper, int limit);
}
