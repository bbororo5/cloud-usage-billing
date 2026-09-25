package io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain;

import java.time.Instant;
import java.util.UUID;

public record HistoryState(boolean initialized, String subject, Instant baseline, Instant through,
                           long sequence, long version, Interval open, boolean occupancyUsed, boolean accountExists) {
    public record Interval(UUID id, String account, Instant start) { }
}
