package io.github.bbororo5.cloudbilling.event;

import java.util.List;

public final class UsageEventValidationException extends RuntimeException {

    private final ValidationStage stage;
    private final List<String> violations;

    public UsageEventValidationException(ValidationStage stage, List<String> violations, Throwable cause) {
        super("usage event validation failed at " + stage, cause);
        this.stage = stage;
        this.violations = List.copyOf(violations);
    }

    public UsageEventValidationException(ValidationStage stage, List<String> violations) {
        this(stage, violations, null);
    }

    public ValidationStage stage() {
        return stage;
    }

    public List<String> violations() {
        return violations;
    }
}
