package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader.Snapshot;
import java.time.Instant;
import java.util.Optional;

public interface SnapshotCache {
  record Key(String source, long version, Instant from, Instant to) {}

  Optional<Snapshot> get(Key key);

  void put(Key key, Snapshot snapshot);
}
