package io.github.bbororo5.cloudbilling.eventapi.auth;

import java.util.Optional;
import java.util.UUID;

interface ProducerCredentialRepository {

    Optional<ProducerCredential> findById(UUID credentialId);
}
