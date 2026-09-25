package io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Normalized wire event. Inapplicable fields are null, not synthetic values. */
public record Event(
    String source,
    String subject,
    UUID id,
    Kind kind,
    long sequence,
    Instant time,
    UUID occupancyId,
    String account,
    Instant baseline,
    Instant through,
    Instant initialStart) {
  public enum Kind {
    INITIALIZED,
    STARTED,
    ENDED,
    CONFIRMED
  }

  public Event {
    Objects.requireNonNull(source);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(time);
    if (source.isBlank() || subject.isBlank() || sequence < 1)
      throw new IllegalArgumentException("Invalid event");
  }
}
