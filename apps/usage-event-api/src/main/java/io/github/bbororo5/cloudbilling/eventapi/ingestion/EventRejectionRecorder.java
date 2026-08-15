package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.eventapi.auth.AuthenticatedProducer;

public interface EventRejectionRecorder {

    void record(
            AuthenticatedProducer producer,
            RejectionStage stage,
            String reasonCode
    );
}
