package io.github.bbororo5.cloudbilling.worker.attribution.domain;

import java.time.Instant;
import java.util.*;

public final class AttributionRules {
  public record Occupancy(UUID id, String account, Instant from, Instant to) {}
  public record Evidence(String source, String subject, Instant baseline, Instant through,
      long version, boolean blocked, List<Occupancy> intervals) {
    public Evidence { intervals = List.copyOf(intervals); }
  }
  public sealed interface Decision permits Assigned, Waiting, Failed {}
  public record Assigned(String account, UUID occupancy, long historyVersion) implements Decision {}
  public record Waiting(String reason) implements Decision {}
  public record Failed(String reason) implements Decision {}

  public Decision decide(Usage usage, Evidence evidence) {
    if (evidence == null) return new Waiting("HISTORY_INCOMPLETE");
    if (!usage.key().source().equals(evidence.source()) || !usage.subject().equals(evidence.subject()))
      return new Failed("SOURCE_MISMATCH");
    if (evidence.blocked()) return new Failed("HISTORY_CONFLICT");
    if (usage.from().isBefore(evidence.baseline()) || usage.to().isAfter(evidence.through()))
      return new Waiting("HISTORY_INCOMPLETE");
    var overlapping = evidence.intervals().stream().filter(i -> i.from().isBefore(usage.to())
        && (i.to() == null || i.to().isAfter(usage.from()))).toList();
    if (overlapping.isEmpty()) return new Failed("UNOCCUPIED");
    if (overlapping.size() != 1) return new Failed("INTERVAL_MISMATCH");
    var i = overlapping.getFirst();
    if (i.from().isAfter(usage.from()) || (i.to() != null && i.to().isBefore(usage.to())))
      return new Failed("INTERVAL_MISMATCH");
    return new Assigned(i.account(), i.id(), evidence.version());
  }
}
