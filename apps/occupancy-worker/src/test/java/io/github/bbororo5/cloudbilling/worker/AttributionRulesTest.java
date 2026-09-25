package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttributionRulesTest {
  final Instant t = Instant.parse("2026-09-01T00:00:00Z");
  final UUID occupancy = UUID.fromString("00000000-0000-0000-0000-000000000001");
  Usage usage() {
    return new Usage(new Usage.Key("urn:vm:a", UUID.randomUUID()), "vm-a", t, t.plusSeconds(60),
        "r1", "vm-a", "VirtualMachine", List.of(
            new Usage.Measurement("cpu", new BigInteger("18446744073709551615"), "second"),
            new Usage.Measurement("memory", BigInteger.TWO, "byte"),
            new Usage.Measurement("network", BigInteger.ZERO, "byte")));
  }
  AttributionRules.Evidence evidence(List<AttributionRules.Occupancy> intervals) {
    return new AttributionRules.Evidence("urn:vm:a", "vm-a", t, t.plusSeconds(120), 7, false, intervals);
  }
  @Test void historicalOwnerAndExactHalfOpenBoundaries() {
    var result = new AttributionRules().decide(usage(), evidence(List.of(
        new AttributionRules.Occupancy(occupancy, "A", t, t.plusSeconds(60)),
        new AttributionRules.Occupancy(UUID.randomUUID(), "B", t.plusSeconds(60), null))));
    assertEquals(new AttributionRules.Assigned("A", occupancy, 7), result);
  }
  @Test void independentPartitionTable() {
    var rules = new AttributionRules(); var u = usage();
    assertEquals(new AttributionRules.Waiting("HISTORY_INCOMPLETE"), rules.decide(u, null));
    assertEquals(new AttributionRules.Failed("UNOCCUPIED"), rules.decide(u, evidence(List.of())));
    assertEquals(new AttributionRules.Failed("INTERVAL_MISMATCH"), rules.decide(u, evidence(List.of(
        new AttributionRules.Occupancy(occupancy,"A",t,t.plusSeconds(35)),
        new AttributionRules.Occupancy(UUID.randomUUID(),"B",t.plusSeconds(35),null)))));
    assertEquals(new AttributionRules.Waiting("HISTORY_INCOMPLETE"), rules.decide(u,
        new AttributionRules.Evidence(u.key().source(),"vm-a",t,t.plusSeconds(59),1,false,List.of())));
    assertEquals(new AttributionRules.Failed("HISTORY_CONFLICT"), rules.decide(u,
        new AttributionRules.Evidence(u.key().source(),"vm-a",t,t.plusSeconds(120),1,true,List.of())));
    assertEquals(new AttributionRules.Failed("SOURCE_MISMATCH"), rules.decide(u,
        new AttributionRules.Evidence("other","vm-a",t,t.plusSeconds(120),1,false,List.of())));
  }
  @Test void unsignedQuantitiesAndImmutableInput() {
    assertEquals(new BigInteger("18446744073709551615"), usage().measurements().getFirst().quantity());
    assertThrows(IllegalArgumentException.class, () -> new Usage.Measurement("cpu", BigInteger.valueOf(-1),"s"));
    assertThrows(IllegalArgumentException.class, () -> new Usage.Measurement("cpu", BigInteger.ONE.shiftLeft(64),"s"));
    assertThrows(UnsupportedOperationException.class, () -> usage().measurements().clear());
  }
}
