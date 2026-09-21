package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import io.github.bbororo5.cloudbilling.eventapi.config.IngestionProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
class KafkaUsageEventPublisher implements UsageEventPublisher {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final IngestionProperties properties;

    KafkaUsageEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            IngestionProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(String partitionKey, byte[] payload) {
        try {
            kafkaTemplate.send(properties.topic(), partitionKey, payload)
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UsageEventPublishException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new UsageEventPublishException(exception);
        }
    }
}
