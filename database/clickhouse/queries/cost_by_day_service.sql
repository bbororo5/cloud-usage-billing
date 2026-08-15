with latest_usage as
(
    select
        event_source,
        event_id,
        sku_meter,
        argMax(charge_period_start, tuple(kafka_partition, kafka_offset)) as charge_period_start,
        argMax(charge_period_end, tuple(kafka_partition, kafka_offset)) as charge_period_end,
        argMax(service_category, tuple(kafka_partition, kafka_offset)) as service_category,
        argMax(sku_id, tuple(kafka_partition, kafka_offset)) as sku_id,
        argMax(consumed_quantity, tuple(kafka_partition, kafka_offset)) as consumed_quantity,
        argMax(consumed_unit, tuple(kafka_partition, kafka_offset)) as consumed_unit,
        uniqExact(payload_hash) as payload_variants
    from billing.usage_record_delivery d
    prewhere d.billing_account_id = {billing_account_id:String}
    where d.charge_period_start >= {from:DateTime64(3, 'UTC')}
      and d.charge_period_end <= {to:DateTime64(3, 'UTC')}
    group by event_source, event_id, sku_meter
),
priced_usage as
(
    select
        u.*,
        p.price_rate_id,
        p.unit_price,
        count(p.price_rate_id) over (
            partition by u.event_source, u.event_id, u.sku_meter
        ) as price_matches
    from latest_usage u
    left join
    (
        select * from billing.price_rate_snapshot final
    ) p
      on p.sku_id = u.sku_id
     and p.service_category = u.service_category
     and p.sku_meter = u.sku_meter
     and p.consumed_unit = u.consumed_unit
     and u.charge_period_start >= p.valid_from
     and u.charge_period_end <= ifNull(
         p.valid_to,
         toDateTime64('2100-01-01 00:00:00.000', 3, 'UTC')
     )
),
grouped_cost as
(
    select
        toDate(charge_period_start) as day,
        service_category,
        max(charge_period_end) as data_as_of,
        sumIf(
            toDecimal128(consumed_quantity, 18) * unit_price,
            payload_variants = 1 and price_matches = 1
        ) as estimated_cost,
        countIf(payload_variants != 1 or price_matches != 1) as quality_errors
    from priced_usage
    group by day, service_category
)
select
    day,
    service_category,
    data_as_of,
    estimated_cost,
    sum(quality_errors) over () as total_quality_errors
from grouped_cost
order by day, service_category
settings join_use_nulls = 1;
