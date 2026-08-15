package io.github.bbororo5.cloudbilling.ledgerwriter.ledger;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
class UsageEventBatchListener {

    private final InstanceUsageEventParser parser;
    private final UsageLedgerRowMapper mapper;
    private final UsageLedgerRepository repository;

    UsageEventBatchListener(
            InstanceUsageEventParser parser,
            UsageLedgerRowMapper mapper,
            UsageLedgerRepository repository
    ) {
        this.parser = parser;
        this.mapper = mapper;
        this.repository = repository;
    }

    @KafkaListener(topics = "${billing.ledger-writer.topic}")
    void consume(
            List<ConsumerRecord<String, byte[]>> deliveries,
            Acknowledgment acknowledgment
    ) {
        List<UsageLedgerRow> rows = new ArrayList<>(deliveries.size() * 3);
        for (ConsumerRecord<String, byte[]> delivery : deliveries) {
            rows.addAll(mapper.map(parser.parse(delivery.value()), delivery));
        }

        repository.append(rows);
        acknowledgment.acknowledge();
    }
}
