package io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain;

public final class OccupancyRules {
    private OccupancyRules() { }
    public static Decision decide(HistoryState s, Event e) {
        if (e.sequence() <= s.sequence()) return new Decision.Duplicate();
        if (s.version() == Long.MAX_VALUE) return new Decision.Reject("VERSION_EXHAUSTED");
        if (e.sequence() != s.sequence() + 1) return new Decision.Wait("SEQUENCE_GAP");
        if (s.initialized() && !s.subject().equals(e.subject())) return new Decision.Reject("SUBJECT_CHANGED");
        if (!s.initialized() && e.kind() != Event.Kind.INITIALIZED) return new Decision.Reject("INITIALIZATION_REQUIRED");
        return switch (e.kind()) {
            case INITIALIZED -> {
                if (s.initialized() || e.sequence() != 1 || e.baseline() == null || e.baseline().isAfter(e.time())
                        || (e.occupancyId() != null && (e.initialStart() == null || e.initialStart().isAfter(e.baseline()))))
                    yield new Decision.Reject("INVALID_BASELINE");
                if (e.occupancyId() != null && !s.accountExists()) yield new Decision.Reject("UNKNOWN_ACCOUNT");
                yield new Decision.Apply(e);
            }
            case STARTED -> {
                if (e.time().isBefore(s.through())) yield new Decision.Reject("CONFIRMED_PAST");
                if (s.open() != null || s.occupancyUsed()) yield new Decision.Reject("OVERLAPPING_OCCUPANCY");
                if (!s.accountExists()) yield new Decision.Reject("UNKNOWN_ACCOUNT");
                yield new Decision.Apply(e);
            }
            case ENDED -> {
                if (e.time().isBefore(s.through())) yield new Decision.Reject("CONFIRMED_PAST");
                if (s.open() == null || !s.open().id().equals(e.occupancyId()) || !e.time().isAfter(s.open().start()))
                    yield new Decision.Reject("INVALID_END");
                yield new Decision.Apply(e);
            }
            case CONFIRMED -> {
                if (e.through() == null || e.through().isBefore(s.through()) || e.through().isAfter(e.time()))
                    yield new Decision.Reject("INVALID_CONFIRMATION");
                yield new Decision.Apply(e);
            }
        };
    }
}
