package io.github.bbororo5.cloudbilling.eventapi.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProducerAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID CREDENTIAL_ID = UUID.fromString("0198a25d-63c7-7c81-9d8c-14e94527c942");
    private static final String SECRET = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);

    private final ProducerCredentialRepository repository = mock(ProducerCredentialRepository.class);
    private final ProducerAuthenticator authenticator = new ProducerAuthenticator(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void authenticatesAnActiveCredential() {
        when(repository.findById(CREDENTIAL_ID)).thenReturn(Optional.of(activeCredential()));

        AuthenticatedProducer producer = authenticator.authenticate(bearer(SECRET));

        assertThat(producer.billingAccountId()).isEqualTo("tenant-001");
        assertThat(producer.producerId()).isEqualTo("generator-01");
    }

    @Test
    void rejectsAnIncorrectSecretWithoutRevealingCredentialDetails() {
        when(repository.findById(CREDENTIAL_ID)).thenReturn(Optional.of(activeCredential()));
        byte[] incorrectSecretBytes = new byte[32];
        incorrectSecretBytes[0] = 1;
        String incorrectSecret = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(incorrectSecretBytes);

        assertThatThrownBy(() -> authenticator.authenticate(bearer(incorrectSecret)))
                .isInstanceOfSatisfying(ProducerAuthenticationException.class, exception ->
                        assertThat(exception.reasonCode()).isEqualTo("INVALID_PRODUCER_CREDENTIAL"));
    }

    @Test
    void rejectsAnExpiredCredential() {
        ProducerCredential expired = new ProducerCredential(
                CREDENTIAL_ID,
                ProducerAuthenticator.hashSecretForStorage(SECRET),
                "tenant-001",
                "generator-01",
                URI.create("urn:cloud-usage:meter:generator-01"),
                "ACTIVE",
                NOW.minusSeconds(120),
                NOW,
                null
        );
        when(repository.findById(CREDENTIAL_ID)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authenticator.authenticate(bearer(SECRET)))
                .isInstanceOfSatisfying(ProducerAuthenticationException.class, exception ->
                        assertThat(exception.reasonCode()).isEqualTo("INACTIVE_PRODUCER_CREDENTIAL"));
    }

    @Test
    void rejectsAMalformedBearerToken() {
        assertThatThrownBy(() -> authenticator.authenticate("Bearer not-a-token"))
                .isInstanceOf(ProducerAuthenticationException.class);
    }

    private ProducerCredential activeCredential() {
        return new ProducerCredential(
                CREDENTIAL_ID,
                ProducerAuthenticator.hashSecretForStorage(SECRET),
                "tenant-001",
                "generator-01",
                URI.create("urn:cloud-usage:meter:generator-01"),
                "ACTIVE",
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                null
        );
    }

    private String bearer(String secret) {
        return "Bearer " + CREDENTIAL_ID + "." + secret;
    }
}
