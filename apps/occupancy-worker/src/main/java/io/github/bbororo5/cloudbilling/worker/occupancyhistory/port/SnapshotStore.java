package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader.*;
import java.time.Instant;
import java.util.*;

public interface SnapshotStore {
  record Head(
      String source,
      String subject,
      Instant baseline,
      Instant through,
      long version,
      boolean initialized,
      UUID conflict) {}

  Optional<Head> head(Query query);

  List<Slice> slices(Query query);
}
