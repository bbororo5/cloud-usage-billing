package io.github.bbororo5.cloudbilling.eventapi.auth;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ProducerAuthenticator {

    private static final int SECRET_BYTES = 32;

    private final ProducerCredentialRepository repository;
    private final Clock clock;

    @Autowired
    public ProducerAuthenticator(ProducerCredentialRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ProducerAuthenticator(ProducerCredentialRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AuthenticatedProducer authenticate(String authorization) {
        ParsedToken token = parse(authorization);
        ProducerCredential credential = repository.findById(token.credentialId())
                .orElseThrow(() -> failure("INVALID_PRODUCER_CREDENTIAL"));

        byte[] expectedHash;
        try {
            expectedHash = HexFormat.of().parseHex(credential.secretHash());
        } catch (IllegalArgumentException exception) {
            throw failure("INVALID_PRODUCER_CREDENTIAL");
        }
        if (!MessageDigest.isEqual(expectedHash, sha256(token.secret()))) {
            throw failure("INVALID_PRODUCER_CREDENTIAL");
        }

        Instant now = clock.instant();
        if (!credential.producerStatus().equals("ACTIVE")
                || credential.revokedAt() != null
                || now.isBefore(credential.validFrom())
                || !now.isBefore(credential.expiresAt())) {
            throw failure("INACTIVE_PRODUCER_CREDENTIAL");
        }

        return new AuthenticatedProducer(
                credential.billingAccountId(),
                credential.producerId(),
                credential.source()
        );
    }

    public static String hashSecretForStorage(String encodedSecret) {
        return HexFormat.of().formatHex(sha256(decodeSecret(encodedSecret)));
    }

    private ParsedToken parse(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw failure("MISSING_PRODUCER_CREDENTIAL");
        }
        String token = authorization.substring("Bearer ".length());
        int separator = token.indexOf('.');
        if (separator < 1 || separator == token.length() - 1) {
            throw failure("INVALID_PRODUCER_CREDENTIAL");
        }
        try {
            UUID credentialId = UUID.fromString(token.substring(0, separator));
            byte[] secret = decodeSecret(token.substring(separator + 1));
            return new ParsedToken(credentialId, secret);
        } catch (IllegalArgumentException exception) {
            throw failure("INVALID_PRODUCER_CREDENTIAL");
        }
    }

    private static byte[] decodeSecret(String encodedSecret) {
        byte[] decoded = Base64.getUrlDecoder().decode(encodedSecret.getBytes(StandardCharsets.US_ASCII));
        if (decoded.length != SECRET_BYTES) {
            throw new IllegalArgumentException("producer secret must be 256 bits");
        }
        return decoded;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ProducerAuthenticationException failure(String reasonCode) {
        return new ProducerAuthenticationException(reasonCode);
    }

    private record ParsedToken(UUID credentialId, byte[] secret) {

        private ParsedToken {
            secret = secret.clone();
        }
    }
}
