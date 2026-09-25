package io.github.bbororo5.cloudbilling.worker.occupancyhistory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HistoryReader {
  Result lookup(Query query);

  record Query(String source, Instant from, Instant to) {
    public Query {
      if (source == null || source.isBlank() || from == null || to == null || !from.isBefore(to))
        throw new InvalidQuery();
    }
  }

  sealed interface Result permits Confirmed, NotReady, Conflict {}

  record Confirmed(Snapshot snapshot) implements Result {}

  record NotReady(Reason reason) implements Result {}

  record Conflict(UUID issueId) implements Result {}

  enum Reason {
    UNREGISTERED,
    BEFORE_BASELINE,
    AWAITING_FACTS
  }

  record Slice(UUID occupancyId, String billingAccountId, Instant startedAt, Instant endedAt) {}

  record Snapshot(
      String source,
      String subject,
      Instant baselineAt,
      Instant confirmedThrough,
      long version,
      List<Slice> slices) {
    public Snapshot {
      slices = List.copyOf(slices);
    }
  }

  final class InvalidQuery extends IllegalArgumentException {
    public InvalidQuery() {
      super("Invalid history query");
    }
  }

  final class HistoryUnavailable extends RuntimeException {
    public HistoryUnavailable(Throwable cause) {
      super("History storage unavailable", cause);
    }
  }
}
