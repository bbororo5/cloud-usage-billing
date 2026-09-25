package io.github.bbororo5.cloudbilling.worker.attribution.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** One immutable source event; measurements are never attributed independently. */
public record Usage(
    Key key,
    String subject,
    Instant from,
    Instant to,
    String region,
    String resource,
    String resourceType,
    List<Measurement> measurements) {
  public Usage {
    Objects.requireNonNull(key);
    if (subject == null || subject.isBlank() || from == null || to == null || !from.isBefore(to))
      throw new IllegalArgumentException("Invalid usage interval");
    Objects.requireNonNull(region);
    Objects.requireNonNull(resource);
    Objects.requireNonNull(resourceType);
    measurements = List.copyOf(measurements);
    if (measurements.size() != 3
        || measurements.stream().map(Measurement::meter).distinct().count() != 3)
      throw new IllegalArgumentException("Expected three distinct measurements");
  }

  public record Key(String source, UUID id) {
    public Key {
      if (source == null || source.isBlank()) throw new IllegalArgumentException("Missing source");
      Objects.requireNonNull(id);
    }
  }

  public record Measurement(String meter, BigInteger quantity, String unit) {
    public Measurement {
      if (meter == null
          || meter.isBlank()
          || unit == null
          || unit.isBlank()
          || quantity == null
          || quantity.signum() < 0
          || quantity.bitLength() > 64) throw new IllegalArgumentException("Invalid measurement");
    }
  }
}
