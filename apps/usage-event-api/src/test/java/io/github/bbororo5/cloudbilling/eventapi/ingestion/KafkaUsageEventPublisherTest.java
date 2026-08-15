package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.eventapi.config.IngestionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaUsageEventPublisherTest {

    private final KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
    private final KafkaUsageEventPublisher publisher = new KafkaUsageEventPublisher(
            template,
            new IngestionProperties("usage-events.v1", Duration.ofSeconds(1), 65_536)
    );

    @Test
    void returnsOnlyAfterKafkaAcknowledgesTheRecord() {
        byte[] payload = {1, 2, 3};
        CompletableFuture<SendResult<String, byte[]>> acknowledged =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(template.send("usage-events.v1", "tenant-001:i-1", payload))
                .thenReturn(acknowledged);

        publisher.publish("tenant-001:i-1", payload);

        verify(template).send("usage-events.v1", "tenant-001:i-1", payload);
    }

    @Test
    void failsTheRequestWhenKafkaDoesNotAcknowledgeTheRecord() {
        CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(template.send("usage-events.v1", "key", new byte[0])).thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish("key", new byte[0]))
                .isInstanceOf(UsageEventPublishException.class);
    }
}
