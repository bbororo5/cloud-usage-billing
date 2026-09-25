package io.github.bbororo5.cloudbilling.worker.attribution.api;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Internal trusted caller only. This is not a customer authorization boundary. */
public interface AttributionReader {
  Result lookup(Query query);

  record Query(String account, String source, UUID eventId) {
    public Query {
      if (account == null
          || account.isBlank()
          || source == null
          || source.isBlank()
          || eventId == null) throw new IllegalArgumentException("Invalid attribution query");
    }
  }

  sealed interface Result permits Ready, Withheld {}

  record Ready(
      UUID revision,
      String account,
      UUID occupancy,
      Instant from,
      Instant to,
      List<Measurement> measurements)
      implements Result {
    public Ready {
      measurements = List.copyOf(measurements);
    }
  }

  record Measurement(String meter, BigInteger quantity, String unit) {}

  record Withheld() implements Result {}

  final class AttributionUnavailable extends RuntimeException {
    public AttributionUnavailable(Throwable cause) {
      super("Attribution unavailable", cause);
    }
  }
}
