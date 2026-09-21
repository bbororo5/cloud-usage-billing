package io.github.bbororo5.cloudbilling.ledgerwriter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("billing.ledger-writer")
public record LedgerWriterProperties(String topic) {

    public LedgerWriterProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("ledger writer topic is required");
        }
    }
}
