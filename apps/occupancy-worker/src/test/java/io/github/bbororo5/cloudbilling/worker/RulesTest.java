package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RulesTest {
  final Instant t = Instant.parse("2026-09-01T10:00:00Z");
  final UUID occupancy = UUID.fromString("00000000-0000-0000-0000-000000000010");

  Event event(Event.Kind kind, long sequence, Instant time, UUID id, Instant through) {
    return new Event(
        "urn:vm:a",
        "instances/a",
        UUID.randomUUID(),
        kind,
        sequence,
        time,
        id,
        "x",
        t,
        through,
        null);
  }

  HistoryState state(long seq, Instant through, HistoryState.Interval open) {
    return new HistoryState(true, "instances/a", t, through, seq, 1, open, false, true);
  }

  @Test
  void boundariesAndFourEvents() {
    var empty = new HistoryState(false, null, null, null, 0, 0, null, false, true);
    assertInstanceOf(
        Decision.Apply.class,
        OccupancyRules.decide(empty, event(Event.Kind.INITIALIZED, 1, t, null, null)));
    assertInstanceOf(
        Decision.Apply.class,
        OccupancyRules.decide(state(1, t, null), event(Event.Kind.STARTED, 2, t, occupancy, null)));
    var open = new HistoryState.Interval(occupancy, "x", t);
    assertEquals(
        "INVALID_END",
        ((Decision.Reject)
                OccupancyRules.decide(
                    state(2, t, open), event(Event.Kind.ENDED, 3, t, occupancy, null)))
            .reason());
    assertInstanceOf(
        Decision.Apply.class,
        OccupancyRules.decide(
            state(2, t, open), event(Event.Kind.ENDED, 3, t.plusSeconds(120), occupancy, null)));
    assertInstanceOf(
        Decision.Apply.class,
        OccupancyRules.decide(
            state(3, t, null),
            event(Event.Kind.CONFIRMED, 4, t.plusSeconds(120), null, t.plusSeconds(120))));
  }

  @Test
  void rejectsInvalidTransitionsAndWaitsForGaps() {
    var open = new HistoryState.Interval(occupancy, "x", t);
    assertInstanceOf(
        Decision.Wait.class,
        OccupancyRules.decide(
            state(1, t, null), event(Event.Kind.ENDED, 3, t.plusSeconds(1), occupancy, null)));
    assertInstanceOf(
        Decision.Duplicate.class,
        OccupancyRules.decide(
            state(3, t, null), event(Event.Kind.ENDED, 3, t.plusSeconds(1), occupancy, null)));
    assertInstanceOf(
        Decision.Reject.class,
        OccupancyRules.decide(
            state(2, t, open), event(Event.Kind.STARTED, 3, t, UUID.randomUUID(), null)));
    assertInstanceOf(
        Decision.Reject.class,
        OccupancyRules.decide(
            state(2, t, open),
            event(Event.Kind.ENDED, 3, t.plusSeconds(1), UUID.randomUUID(), null)));
    assertInstanceOf(
        Decision.Reject.class,
        OccupancyRules.decide(
            state(2, t.plusSeconds(60), open),
            event(Event.Kind.ENDED, 3, t.plusSeconds(59), occupancy, null)));
    assertInstanceOf(
        Decision.Apply.class,
        OccupancyRules.decide(
            state(2, t.plusSeconds(60), open),
            event(Event.Kind.ENDED, 3, t.plusSeconds(60), occupancy, null)));
    var max = new HistoryState(true, "instances/a", t, t, Long.MAX_VALUE, 1, null, false, true);
    assertInstanceOf(
        Decision.Duplicate.class,
        OccupancyRules.decide(max, event(Event.Kind.CONFIRMED, Long.MAX_VALUE, t, null, t)));
    var maxVersion =
        new HistoryState(true, "instances/a", t, t, 1, Long.MAX_VALUE, null, false, true);
    assertInstanceOf(
        Decision.Reject.class,
        OccupancyRules.decide(maxVersion, event(Event.Kind.STARTED, 2, t, occupancy, null)));
  }
}
