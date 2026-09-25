package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import java.util.List;
import java.util.Optional;

public interface HistoryStore {
  record Pending(HistoryState state, Event event) {}

  List<String> due(int limit);

  Optional<Pending> lockAndLoadNext(String source);

  Decision persist(String source, Decision decision);
}
