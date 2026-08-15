package io.github.bbororo5.cloudbilling.eventapi.ingestion;

public final class UsageEventPublishException extends RuntimeException {

    public UsageEventPublishException(Throwable cause) {
        super("failed to durably publish usage event", cause);
    }
}
