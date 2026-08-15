-- Local development login roles. Production credentials are provisioned externally.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'billing_ingestion') then
        create role billing_ingestion login password 'local-dev-only'
            nosuperuser nocreatedb nocreaterole noinherit nobypassrls;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'billing_bff') then
        create role billing_bff login password 'local-dev-only'
            nosuperuser nocreatedb nocreaterole noinherit nobypassrls;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'billing_batch') then
        create role billing_batch login password 'local-dev-only'
            nosuperuser nocreatedb nocreaterole noinherit nobypassrls;
    end if;
end;
$$;

grant usage on schema billing to billing_ingestion, billing_bff, billing_batch;

grant select on billing.usage_producer, billing.producer_credential
    to billing_ingestion;
grant insert on billing.event_rejection to billing_ingestion;

grant select on billing.app_user, billing.billing_account,
    billing.billing_membership, billing.monthly_settlement
    to billing_bff;
grant insert, update, delete on billing.billing_membership to billing_bff;
grant insert on billing.security_audit_event to billing_bff;
grant select, insert, update, delete on billing.spring_session,
    billing.spring_session_attributes to billing_bff;

grant select on billing.billing_account, billing.clickhouse_price_rate_export,
    billing.settlement_job, billing.settlement_attempt,
    billing.settlement_validation, billing.monthly_settlement
    to billing_batch;
grant insert, update on billing.settlement_job, billing.settlement_attempt
    to billing_batch;
grant insert on billing.settlement_validation, billing.monthly_settlement
    to billing_batch;
