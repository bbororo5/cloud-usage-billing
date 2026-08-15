package io.github.bbororo5.cloudbilling.eventapi.auth;

public final class ProducerAuthenticationException extends RuntimeException {

    private final String reasonCode;

    ProducerAuthenticationException(String reasonCode) {
        super("producer authentication failed");
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
