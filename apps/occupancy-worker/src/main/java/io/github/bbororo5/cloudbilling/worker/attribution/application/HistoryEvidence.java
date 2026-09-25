package io.github.bbororo5.cloudbilling.worker.attribution.application;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;

final class HistoryEvidence {
  private HistoryEvidence() {}

  static AttributionRules.Decision decide(Usage u, HistoryReader.Result result) {
    if (result instanceof HistoryReader.NotReady)
      return new AttributionRules.Waiting("HISTORY_INCOMPLETE");
    if (result instanceof HistoryReader.Conflict)
      return new AttributionRules.Failed("HISTORY_CONFLICT");
    var s = ((HistoryReader.Confirmed) result).snapshot();
    return new AttributionRules()
        .decide(
            u,
            new AttributionRules.Evidence(
                s.source(),
                s.subject(),
                s.baselineAt(),
                s.confirmedThrough(),
                s.version(),
                false,
                s.slices().stream()
                    .map(
                        i ->
                            new AttributionRules.Occupancy(
                                i.occupancyId(), i.billingAccountId(), i.startedAt(), i.endedAt()))
                    .toList()));
  }

  static HistoryReader.Query query(Usage u) {
    return new HistoryReader.Query(u.key().source(), u.from(), u.to());
  }

  static boolean sameOwner(Prepared p, AttributionRules.Decision d) {
    return d instanceof AttributionRules.Assigned a
        && p.account().equals(a.account())
        && p.occupancy().equals(a.occupancy());
  }
}
