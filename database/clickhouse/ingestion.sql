-- Apply after the usage-clickhouse-v1 group has been explicitly initialized.
create table if not exists billing.usage_kafka
(
    specversion String, id UUID, source String, type String, subject String,
    time String, datacontenttype String, dataschema String,
    data Array(Tuple(ChargePeriodStart String, ChargePeriodEnd String, RegionId String,
        ResourceId String, ResourceType String, Meter String, ConsumedQuantity UInt64, ConsumedUnit String))
)
engine = Kafka
settings kafka_broker_list='kafka-1:9092,kafka-2:9092,kafka-3:9092',
    kafka_topic_list='usage-events.v1', kafka_group_name='usage-clickhouse-v1',
    kafka_format='JSONEachRow', kafka_num_consumers=1,
    kafka_max_block_size=1000, kafka_flush_interval_ms=1000,
    kafka_poll_timeout_ms=1000, kafka_thread_per_consumer=1,
    kafka_commit_every_batch=0, kafka_commit_on_select=0,
    kafka_skip_broken_messages=0, kafka_handle_error_mode='default',
    input_format_skip_unknown_fields=0,
    input_format_json_named_tuples_as_objects=1,
    input_format_json_defaults_for_missing_elements_in_named_tuple=0;

create materialized view if not exists billing.usage_kafka_to_delivery
to billing.usage_record_delivery as
select source as event_source, id as event_id,
    parseDateTime64BestEffort(time,3,'UTC') as event_time,
    subject as event_subject,
    parseDateTime64BestEffort(data[1].ChargePeriodStart,3,'UTC') as charge_period_start,
    parseDateTime64BestEffort(data[1].ChargePeriodEnd,3,'UTC') as charge_period_end,
    data[1].RegionId as region_id, data[1].ResourceId as resource_id,
    data[1].ResourceType as resource_type,
    arrayMap(x -> tuple(x.Meter,x.ConsumedQuantity,x.ConsumedUnit),data) as measurements,
    _topic as kafka_topic, _partition as kafka_partition, _offset as kafka_offset
from billing.usage_kafka;
