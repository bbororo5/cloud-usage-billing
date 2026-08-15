package io.github.bbororo5.cloudbilling.eventapi.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProducerCredentialRepository implements ProducerCredentialRepository {

    private final JdbcClient jdbcClient;

    JdbcProducerCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ProducerCredential> findById(UUID credentialId) {
        return jdbcClient.sql("""
                        select c.credential_id,
                               c.secret_hash,
                               c.billing_account_id,
                               c.producer_id,
                               p.source,
                               p.status as producer_status,
                               c.valid_from,
                               c.expires_at,
                               c.revoked_at
                          from billing.producer_credential c
                          join billing.usage_producer p
                            on p.billing_account_id = c.billing_account_id
                           and p.producer_id = c.producer_id
                         where c.credential_id = :credential_id
                        """)
                .param("credential_id", credentialId)
                .query((resultSet, rowNumber) -> new ProducerCredential(
                        resultSet.getObject("credential_id", UUID.class),
                        resultSet.getString("secret_hash"),
                        resultSet.getString("billing_account_id"),
                        resultSet.getString("producer_id"),
                        URI.create(resultSet.getString("source")),
                        resultSet.getString("producer_status"),
                        resultSet.getTimestamp("valid_from").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getTimestamp("revoked_at") == null
                                ? null
                                : resultSet.getTimestamp("revoked_at").toInstant()
                ))
                .optional();
    }
}
