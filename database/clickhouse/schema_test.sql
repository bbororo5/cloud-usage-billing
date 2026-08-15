insert into billing.usage_record_delivery
(
    billing_account_id, event_source, event_id, event_time, event_subject,
    charge_period_start, charge_period_end, region_id, resource_id, resource_type,
    service_category, service_name, sku_id, sku_meter,
    consumed_quantity, consumed_unit, payload_hash,
    kafka_topic, kafka_partition, kafka_offset
)
values
(
    'tenant-001', 'urn:cloud-usage:meter:generator-01',
    '0198a25d-63c7-7c81-9d8c-14e94527c941', '2026-08-12 00:01:00.000',
    'instances/i-000123', '2026-08-12 00:00:00.000', '2026-08-12 00:01:00.000',
    'kr-central-1', 'i-000123', 'Virtual Machine',
    'Compute', 'Compute', 'compute-medium-linux', 'Compute Usage',
    60, 'Second', repeat('a', 64), 'usage.v1', 0, 10
),
(
    'tenant-001', 'urn:cloud-usage:meter:generator-01',
    '0198a25d-63c7-7c81-9d8c-14e94527c941', '2026-08-12 00:01:00.000',
    'instances/i-000123', '2026-08-12 00:00:00.000', '2026-08-12 00:01:00.000',
    'kr-central-1', 'i-000123', 'Virtual Machine',
    'Compute', 'Compute', 'compute-medium-linux', 'Compute Usage',
    60, 'Second', repeat('a', 64), 'usage.v1', 0, 11
),
(
    'tenant-002', 'urn:cloud-usage:meter:generator-02',
    '0198a25d-63c7-7c82-9d8c-14e94527c942', '2026-08-12 00:01:00.000',
    'instances/i-000123', '2026-08-12 00:00:00.000', '2026-08-12 00:01:00.000',
    'kr-central-1', 'i-000123', 'Virtual Machine',
    'Compute', 'Compute', 'compute-medium-linux', 'Compute Usage',
    120, 'Second', repeat('b', 64), 'usage.v1', 1, 20
);

insert into billing.price_rate_snapshot
(
    price_rate_id, sku_id, service_category, sku_meter, consumed_unit,
    valid_from, valid_to,
    unit_price, currency, sync_version
)
values
(
    '0198a25d-63c7-7101-8000-000000000101',
    'compute-medium-linux', 'Compute', 'Compute Usage', 'Second',
    '2026-08-01 00:00:00.000', null,
    0.001000000000000000, 'KRW', 1
),
(
    '0198a25d-63c7-7101-8000-000000000101',
    'compute-medium-linux', 'Compute', 'Compute Usage', 'Second',
    '2026-08-01 00:00:00.000', '2026-09-01 00:00:00.000',
    0.001000000000000000, 'KRW', 2
);

select throwIf(
    count() != 1 or sum(quantity) != 60,
    'deduplication or tenant scope failed'
)
from
(
    select
        event_source,
        event_id,
        sku_meter,
        argMax(consumed_quantity, tuple(kafka_partition, kafka_offset)) as quantity
    from billing.usage_record_delivery
    prewhere billing_account_id = 'tenant-001'
    where charge_period_start >= '2026-08-12 00:00:00.000'
      and charge_period_start < '2026-08-13 00:00:00.000'
    group by event_source, event_id, sku_meter
);

select throwIf(
    count() != 1 or countIf(valid_to = toDateTime64('2026-09-01 00:00:00.000', 3, 'UTC')) != 1,
    'latest price snapshot was not selected'
)
from billing.price_rate_snapshot final
where sku_id = 'compute-medium-linux';

select 'clickhouse schema tests passed' as result;
