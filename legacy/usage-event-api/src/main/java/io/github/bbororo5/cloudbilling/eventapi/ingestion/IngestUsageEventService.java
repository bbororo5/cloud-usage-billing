package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEvent;
import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import io.github.bbororo5.cloudbilling.event.UsageEventValidationException;
import io.github.bbororo5.cloudbilling.eventapi.auth.AuthenticatedProducer;
import io.github.bbororo5.cloudbilling.eventapi.auth.ProducerAuthenticationException;
import io.github.bbororo5.cloudbilling.eventapi.auth.ProducerAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestUsageEventService {

    private static final Logger log = LoggerFactory.getLogger(IngestUsageEventService.class);

    private final ProducerAuthenticator authenticator;
    private final InstanceUsageEventParser parser;
    private final UsageEventPublisher publisher;
    private final EventRejectionRecorder rejectionRecorder;

    public IngestUsageEventService(
            ProducerAuthenticator authenticator,
            InstanceUsageEventParser parser,
            UsageEventPublisher publisher,
            EventRejectionRecorder rejectionRecorder
    ) {
        this.authenticator = authenticator;
        this.parser = parser;
        this.publisher = publisher;
        this.rejectionRecorder = rejectionRecorder;
    }

    public void ingest(String authorization, byte[] payload) {
        AuthenticatedProducer producer;
        try {
            producer = authenticator.authenticate(authorization);
        } catch (ProducerAuthenticationException exception) {
            recordSafely(null, RejectionStage.AUTHENTICATION, exception.reasonCode());
            throw exception;
        }

        InstanceUsageEvent event;
        try {
            event = parser.parse(payload);
        } catch (UsageEventValidationException exception) {
            String reason = exception.violations().isEmpty()
                    ? "EVENT_CONTRACT_VIOLATION"
                    : exception.violations().getFirst();
            recordSafely(producer, RejectionStage.from(exception.stage()), reason);
            throw exception;
        }

        if (!producer.source().equals(event.source())) {
            recordSafely(producer, RejectionStage.SEMANTIC, "PRODUCER_SCOPE_MISMATCH");
            throw new ProducerScopeException();
        }

        publisher.publish(event.source().toString(), payload);
    }

    private void recordSafely(
            AuthenticatedProducer producer,
            RejectionStage stage,
            String reasonCode
    ) {
        try {
            rejectionRecorder.record(producer, stage, reasonCode);
        } catch (RuntimeException exception) {
            log.error("Failed to record event rejection for stage {}", stage, exception);
        }
    }
}
