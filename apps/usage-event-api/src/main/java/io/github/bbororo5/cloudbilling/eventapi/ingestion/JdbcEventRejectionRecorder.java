package io.github.bbororo5.cloudbilling.eventapi.ingestion;

import com.github.f4b6a3.uuid.UuidCreator;
import io.github.bbororo5.cloudbilling.eventapi.auth.AuthenticatedProducer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;

@Repository
class JdbcEventRejectionRecorder implements EventRejectionRecorder {

    private final JdbcClient jdbcClient;

    JdbcEventRejectionRecorder(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void record(
            AuthenticatedProducer producer,
            RejectionStage stage,
            String reasonCode
    ) {
        jdbcClient.sql("""
                        insert into billing.event_rejection (
                            rejection_id,
                            billing_account_id,
                            producer_id,
                            rejection_stage,
                            reason_code
                        ) values (
                            :rejection_id,
                            :billing_account_id,
                            :producer_id,
                            :rejection_stage,
                            :reason_code
                        )
                        """)
                .param("rejection_id", UuidCreator.getTimeOrderedEpoch())
                .param("billing_account_id", producer == null ? null : producer.billingAccountId(), Types.VARCHAR)
                .param("producer_id", producer == null ? null : producer.producerId(), Types.VARCHAR)
                .param("rejection_stage", stage.name())
                .param("reason_code", reasonCode)
                .update();
    }
}
