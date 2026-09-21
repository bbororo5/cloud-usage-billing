-- Disposable database only. Separate batches defeat block-level deduplication.
insert into billing.usage_record_delivery
(event_source,event_id,event_time,event_subject,charge_period_start,charge_period_end,
 region_id,resource_id,resource_type,measurements,kafka_topic,kafka_partition,kafka_offset)
values ('urn:cloud-usage:meter:test-1','00000000-0000-4000-8000-000000000001',
'2026-08-12 00:01:00.123','instances/vm-1','2026-08-12 00:00:00.123','2026-08-12 00:01:00.123',
'kr-central-1','vm-1','Virtual Machine',
[('Compute Usage',60,'Second'),('Block Volume Usage',6000,'GiB-Second'),('Data Transfer',18446744073709551615,'Byte')],
'usage-events.v1',0,10);

insert into billing.usage_record_delivery select * from billing.usage_record_delivery;
insert into billing.usage_record_delivery
select * replace (toUInt64(9) as kafka_offset) from billing.usage_record_delivery limit 1;
select throwIf(count()!=3, 'physical delivery copies must remain') from billing.usage_record_delivery;
select throwIf(count()!=1 or sum(length(measurements))!=3, 'event deduplication failed') from billing.usage_event;
select throwIf(measurements[1].quantity!=60 or measurements[2].quantity!=6000
    or measurements[3].quantity!=toUInt64('18446744073709551615')
    or event_time!=toDateTime64('2026-08-12 00:01:00.123',3,'UTC'), 'payload lost precision') from billing.usage_event;

insert into billing.usage_record_delivery
select * replace ('urn:cloud-usage:meter:test-2' as event_source) from billing.usage_record_delivery limit 1;
insert into billing.usage_record_delivery
select * replace (toUUID('00000000-0000-4000-8000-000000000002') as event_id,
 charge_period_start + interval 60 second as charge_period_start,
 charge_period_end + interval 60 second as charge_period_end,
 event_time + interval 60 second as event_time) from billing.usage_record_delivery limit 1;
select throwIf(count()!=3 or sum(length(measurements))!=9, 'source or event scope collapsed') from billing.usage_event;
select throwIf(sum(m.quantity)!=180, 'logical compute usage changed')
from billing.usage_event array join measurements as m where m.meter='Compute Usage';
select throwIf(position(create_table_query,'fsync_after_insert = 1')=0
    or position(create_table_query,'fsync_part_directory = 1')=0,'durability settings missing')
from system.tables where database='billing' and name='usage_record_delivery';
select throwIf(engine!='View','deduplication must be a normal view')
from system.tables where database='billing' and name='usage_event';
insert into billing.price_rate_snapshot
(price_rate_id,sku_id,service_category,sku_meter,consumed_unit,valid_from,valid_to,unit_price,currency,sync_version)
values ('00000000-0000-4000-8000-000000000101','compute-medium-linux','Compute','Compute Usage','Second',
'2026-08-01 00:00:00',null,0.001,'KRW',1),
('00000000-0000-4000-8000-000000000101','compute-medium-linux','Compute','Compute Usage','Second',
'2026-08-01 00:00:00','2026-09-01 00:00:00',0.001,'KRW',2);
select throwIf(count()!=1 or countIf(valid_to=toDateTime64('2026-09-01 00:00:00',3,'UTC'))!=1,
'latest price snapshot was not selected') from billing.price_rate_snapshot final;
select 'clickhouse storage tests passed';
