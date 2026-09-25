package io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain;

public sealed interface Decision {
  record Apply(Event event) implements Decision {}

  record Wait(String reason) implements Decision {}

  record Reject(String reason) implements Decision {}

  record Duplicate() implements Decision {}
}
