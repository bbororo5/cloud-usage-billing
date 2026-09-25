-- Append-only prepared revisions. PostgreSQL alone selects the approved revision.
create table if not exists billing.attribution_delivery (
  event_source String, event_id UUID, revision UUID, payload String,
  ingested_at DateTime64(3) default now64(3)
) engine=MergeTree order by (event_source,event_id,revision)
settings fsync_after_insert=1,fsync_part_directory=1;
create view if not exists billing.attribution_revision as
select event_source,event_id,revision,any(d.payload) as payload,uniqExact(d.payload) as variants
from billing.attribution_delivery d group by event_source,event_id,revision;
create user if not exists billing_attribution identified with sha256_password by 'local-attribution-only';
grant select on billing.usage_event to billing_attribution;
-- usage_event is an invoker view: the worker also needs its underlying raw ledger.
grant select on billing.usage_record_delivery to billing_attribution;
grant select,insert on billing.attribution_delivery to billing_attribution;
grant select on billing.attribution_revision to billing_attribution;
-- No BFF/batch/customer grants. Raw/prepared revisions are not a customer read model.
