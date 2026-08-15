package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
class UsageLedgerRepository {

    private static final String INSERT_SQL = """
            insert into billing.usage_record_delivery (
                billing_account_id,
                event_source,
                event_id,
                event_time,
                event_subject,
                charge_period_start,
                charge_period_end,
                region_id,
                resource_id,
                resource_type,
                service_category,
                service_name,
                sku_id,
                sku_meter,
                consumed_quantity,
                consumed_unit,
                payload_hash,
                kafka_topic,
                kafka_partition,
                kafka_offset
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    UsageLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void append(List<UsageLedgerRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                UsageLedgerRow row = rows.get(index);
                statement.setString(1, row.billingAccountId());
                statement.setString(2, row.eventSource().toString());
                statement.setObject(3, row.eventId());
                statement.setTimestamp(4, Timestamp.from(row.eventTime()));
                statement.setString(5, row.eventSubject());
                statement.setTimestamp(6, Timestamp.from(row.chargePeriodStart()));
                statement.setTimestamp(7, Timestamp.from(row.chargePeriodEnd()));
                statement.setString(8, row.regionId());
                statement.setString(9, row.resourceId());
                statement.setString(10, row.resourceType());
                statement.setString(11, row.serviceCategory());
                statement.setString(12, row.serviceName());
                statement.setString(13, row.skuId());
                statement.setString(14, row.skuMeter());
                statement.setLong(15, row.consumedQuantity());
                statement.setString(16, row.consumedUnit());
                statement.setString(17, row.payloadHash());
                statement.setString(18, row.kafkaTopic());
                statement.setInt(19, row.kafkaPartition());
                statement.setLong(20, row.kafkaOffset());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
