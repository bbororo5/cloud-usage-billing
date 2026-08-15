package io.github.bbororo5.cloudbilling.eventapi.auth;

import java.net.URI;

public record AuthenticatedProducer(
        String billingAccountId,
        String producerId,
        URI source
) {
}
