create database if not exists billing;

create table if not exists billing.usage_record_delivery
(
    event_source String,
    event_id UUID,
    event_time DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    event_subject String,
    charge_period_start DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    charge_period_end DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    region_id String,
    resource_id String,
    resource_type String,
    measurements Array(Tuple(meter String, quantity UInt64, unit String)),
    kafka_topic LowCardinality(String),
    kafka_partition UInt32,
    kafka_offset UInt64,
    ingested_at DateTime64(3, 'UTC') default now64(3)
)
engine = MergeTree
partition by toYYYYMM(charge_period_start)
order by (
    event_source,
    charge_period_start,
    event_id
)
settings fsync_after_insert = 1, fsync_part_directory = 1;

-- Select a whole event, not each measurement/column independently. Source+ID is the identity.
create view if not exists billing.usage_event as
select event_source, event_id,
    selected.1 as event_time, selected.2 as event_subject,
    selected.3 as charge_period_start, selected.4 as charge_period_end,
    selected.5 as region_id, selected.6 as resource_id, selected.7 as resource_type,
    selected.8 as measurements
from (
    select event_source, event_id,
        argMax(tuple(event_time,event_subject,charge_period_start,charge_period_end,
                     region_id,resource_id,resource_type,measurements),
               tuple(kafka_topic,kafka_partition,kafka_offset)) as selected
    from billing.usage_record_delivery
    group by event_source, event_id
);

create table if not exists billing.price_rate_snapshot
(
    price_rate_id UUID,
    sku_id LowCardinality(String),
    service_category LowCardinality(String),
    sku_meter LowCardinality(String),
    consumed_unit LowCardinality(String),
    valid_from DateTime64(3, 'UTC'),
    valid_to Nullable(DateTime64(3, 'UTC')),
    unit_price Decimal(30, 18),
    currency FixedString(3),
    sync_version UInt64,
    synced_at DateTime64(3, 'UTC') default now64(3),
    constraint price_sku_not_blank check length(sku_id) > 0,
    constraint price_service_valid check
        service_category in ('Compute', 'Storage', 'Networking'),
    constraint price_meter_not_blank check length(sku_meter) > 0,
    constraint price_unit_valid check
        consumed_unit in ('Second', 'GiB-Second', 'Byte'),
    constraint price_period_valid check isNull(valid_to) or valid_to > valid_from,
    constraint price_nonnegative check unit_price >= 0,
    constraint price_currency_krw check currency = 'KRW'
)
engine = ReplacingMergeTree(sync_version)
order by (sku_id, valid_from, price_rate_id);
