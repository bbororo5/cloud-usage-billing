create database if not exists billing;

create table if not exists billing.usage_record_delivery
(
    billing_account_id LowCardinality(String),
    event_source String,
    event_id UUID,
    event_time DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    event_subject String,
    charge_period_start DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    charge_period_end DateTime64(3, 'UTC') codec(Delta, ZSTD(1)),
    region_id LowCardinality(String),
    resource_id String,
    resource_type LowCardinality(String),
    service_category LowCardinality(String),
    service_name LowCardinality(String),
    sku_id LowCardinality(String),
    sku_meter LowCardinality(String),
    consumed_quantity UInt64,
    consumed_unit LowCardinality(String),
    payload_hash FixedString(64),
    kafka_topic LowCardinality(String),
    kafka_partition UInt16,
    kafka_offset UInt64,
    ingested_at DateTime64(3, 'UTC') default now64(3),
    constraint usage_billing_account_not_blank check length(billing_account_id) > 0,
    constraint usage_event_source_not_blank check length(event_source) > 0,
    constraint usage_period_valid check charge_period_end > charge_period_start,
    constraint usage_period_maximum check
        dateDiff('millisecond', charge_period_start, charge_period_end) between 1 and 60000,
    constraint usage_service_valid check
        service_category in ('Compute', 'Storage', 'Networking'),
    constraint usage_unit_valid check
        consumed_unit in ('Second', 'GiB-Second', 'Byte'),
    constraint usage_payload_hash_valid check length(payload_hash) = 64,
    constraint usage_kafka_topic_not_blank check length(kafka_topic) > 0
)
engine = MergeTree
partition by toYYYYMM(charge_period_start)
order by (
    billing_account_id,
    toDate(charge_period_start),
    service_category,
    resource_id,
    charge_period_start,
    event_source,
    event_id,
    sku_meter
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
