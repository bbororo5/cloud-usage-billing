package io.github.bbororo5.cloudbilling.eventapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("billing.ingestion")
public record IngestionProperties(
        String topic,
        Duration publishTimeout,
        int maxPayloadBytes
) {

    public IngestionProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("ingestion topic is required");
        }
        if (publishTimeout == null || publishTimeout.isNegative() || publishTimeout.isZero()) {
            throw new IllegalArgumentException("publish timeout must be positive");
        }
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("max payload bytes must be positive");
        }
    }
}
