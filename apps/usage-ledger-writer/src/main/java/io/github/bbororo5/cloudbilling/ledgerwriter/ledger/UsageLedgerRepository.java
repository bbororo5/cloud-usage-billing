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
                event_source,
                event_id,
                event_time,
                event_subject,
                charge_period_start,
                charge_period_end,
                region_id,
                resource_id,
                resource_type,
                meter,
                consumed_quantity,
                consumed_unit,
                payload_hash,
                kafka_topic,
                kafka_partition,
                kafka_offset
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                statement.setString(1, row.eventSource().toString());
                statement.setObject(2, row.eventId());
                statement.setTimestamp(3, Timestamp.from(row.eventTime()));
                statement.setString(4, row.eventSubject());
                statement.setTimestamp(5, Timestamp.from(row.chargePeriodStart()));
                statement.setTimestamp(6, Timestamp.from(row.chargePeriodEnd()));
                statement.setString(7, row.regionId());
                statement.setString(8, row.resourceId());
                statement.setString(9, row.resourceType());
                statement.setString(10, row.meter());
                statement.setLong(11, row.consumedQuantity());
                statement.setString(12, row.consumedUnit());
                statement.setString(13, row.payloadHash());
                statement.setString(14, row.kafkaTopic());
                statement.setInt(15, row.kafkaPartition());
                statement.setLong(16, row.kafkaOffset());
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
