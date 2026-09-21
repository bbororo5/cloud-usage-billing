package io.github.bbororo5.cloudbilling.eventapi.ingestion;

public final class ProducerScopeException extends RuntimeException {

    public ProducerScopeException() {
        super("producer is not allowed to publish the event scope");
    }
}
