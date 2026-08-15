package io.github.bbororo5.cloudbilling.eventapi.ingestion;

public interface UsageEventPublisher {

    void publish(String partitionKey, byte[] payload);
}
