with latest_usage as
(
    select
        event_source,
        event_id,
        sku_meter,
        argMax(charge_period_start, tuple(kafka_partition, kafka_offset)) as charge_period_start,
        argMax(charge_period_end, tuple(kafka_partition, kafka_offset)) as charge_period_end,
        argMax(resource_id, tuple(kafka_partition, kafka_offset)) as resource_id,
        argMax(service_category, tuple(kafka_partition, kafka_offset)) as service_category,
        argMax(consumed_quantity, tuple(kafka_partition, kafka_offset)) as consumed_quantity,
        argMax(consumed_unit, tuple(kafka_partition, kafka_offset)) as consumed_unit,
        uniqExact(payload_hash) as payload_variants
    from billing.usage_record_delivery d
    prewhere d.billing_account_id = {billing_account_id:String}
    where d.charge_period_start >= {from:DateTime64(3, 'UTC')}
      and d.charge_period_end <= {to:DateTime64(3, 'UTC')}
      and d.ingested_at <= {snapshot_ingested_at:DateTime64(3, 'UTC')}
    group by event_source, event_id, sku_meter
)
select
    event_source,
    event_id,
    charge_period_start,
    charge_period_end,
    resource_id,
    service_category,
    sku_meter,
    consumed_quantity,
    consumed_unit,
    payload_variants
from latest_usage
where {has_cursor:UInt8} = 0
   or tuple(charge_period_end, event_source, event_id, sku_meter) > tuple(
       {cursor_charge_period_end:DateTime64(3, 'UTC')},
       {cursor_event_source:String},
       {cursor_event_id:UUID},
       {cursor_sku_meter:String}
   )
order by charge_period_end, event_source, event_id, sku_meter
limit {page_size:UInt16} + 1;
