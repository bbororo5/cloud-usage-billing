package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.event.ValidationStage;

public enum RejectionStage {
    AUTHENTICATION,
    ENVELOPE,
    SCHEMA,
    SEMANTIC;

    static RejectionStage from(ValidationStage stage) {
        return valueOf(stage.name());
    }
}
