package io.github.bbororo5.cloudbilling.eventapi.auth;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record ProducerCredential(
        UUID credentialId,
        String secretHash,
        String billingAccountId,
        String producerId,
        URI source,
        String producerStatus,
        Instant validFrom,
        Instant expiresAt,
        Instant revokedAt
) {
}
