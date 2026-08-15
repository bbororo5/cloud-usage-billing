package io.github.bbororo5.cloudbilling.event;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InstanceUsageEvent(
        UUID id,
        URI source,
        String subject,
        OffsetDateTime time,
        List<UsageRecord> records
) {

    public InstanceUsageEvent {
        records = List.copyOf(records);
    }
}
