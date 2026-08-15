package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import io.github.bbororo5.cloudbilling.event.UsageEventValidationException;
import io.github.bbororo5.cloudbilling.eventapi.auth.AuthenticatedProducer;
import io.github.bbororo5.cloudbilling.eventapi.auth.ProducerAuthenticator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestUsageEventServiceTest {

    private final ProducerAuthenticator authenticator = mock(ProducerAuthenticator.class);
    private final UsageEventPublisher publisher = mock(UsageEventPublisher.class);
    private final EventRejectionRecorder rejectionRecorder = mock(EventRejectionRecorder.class);
    private final IngestUsageEventService service = new IngestUsageEventService(
            authenticator,
            new InstanceUsageEventParser(),
            publisher,
            rejectionRecorder
    );

    @Test
    void publishesTheOriginalPayloadUsingTenantAndResourceAsThePartitionKey() {
        byte[] payload = exampleBytes();
        AuthenticatedProducer producer = producer("urn:cloud-usage:meter:generator-01");
        when(authenticator.authenticate("credential")).thenReturn(producer);

        service.ingest("credential", payload);

        verify(publisher).publish("tenant-001:i-000123", payload);
        verify(rejectionRecorder, never()).record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsAnEventOutsideTheAuthenticatedProducerScope() {
        byte[] payload = exampleBytes();
        AuthenticatedProducer producer = producer("urn:cloud-usage:meter:different-generator");
        when(authenticator.authenticate("credential")).thenReturn(producer);

        assertThatThrownBy(() -> service.ingest("credential", payload))
                .isInstanceOf(ProducerScopeException.class);

        verify(publisher, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(rejectionRecorder).record(producer, RejectionStage.SEMANTIC, "PRODUCER_SCOPE_MISMATCH");
    }

    @Test
    void recordsContractViolationsWithoutPublishingThem() {
        byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
        AuthenticatedProducer producer = producer("urn:cloud-usage:meter:generator-01");
        when(authenticator.authenticate("credential")).thenReturn(producer);

        assertThatThrownBy(() -> service.ingest("credential", malformed))
                .isInstanceOf(UsageEventValidationException.class);

        verify(rejectionRecorder).record(
                producer,
                RejectionStage.ENVELOPE,
                "INVALID_JSON"
        );
        verify(publisher, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private AuthenticatedProducer producer(String source) {
        return new AuthenticatedProducer("tenant-001", "generator-01", URI.create(source));
    }

    private byte[] exampleBytes() {
        try (InputStream input = getClass().getResourceAsStream(
                "/examples/instance-usage-event.json"
        )) {
            if (input == null) {
                throw new IllegalStateException("event example resource is missing");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read event example", exception);
        }
    }
}
