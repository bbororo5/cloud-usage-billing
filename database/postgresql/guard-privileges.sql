-- Apply after schema.sql and local-roles.sql, using the schema deployment account.
-- No application login receives the locking privileges below.
begin;

do $$
declare
    guard_name text;
begin
    foreach guard_name in array array['billing_membership_guard', 'billing_settlement_guard'] loop
        if not exists (select 1 from pg_roles where rolname = guard_name) then
            execute format('create role %I nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls', guard_name);
        end if;
        if exists (
            select 1 from pg_roles where rolname = guard_name
            and (rolcanlogin or rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls)
        ) or exists (
            select 1 from pg_roles where rolname <> guard_name
            and pg_has_role(guard_name, oid, 'MEMBER')
        ) then
            raise exception 'unsafe guard role: %', guard_name;
        end if;
    end loop;
end;
$$;

grant usage on schema billing to billing_membership_guard, billing_settlement_guard;
grant select on billing.billing_account, billing.billing_membership to billing_membership_guard;
-- PostgreSQL row locks require UPDATE on at least one column; no table-wide UPDATE.
grant update (billing_account_id) on billing.billing_account to billing_membership_guard;
grant select on billing.settlement_attempt, billing.settlement_validation to billing_settlement_guard;
grant update (run_id) on billing.settlement_attempt, billing.settlement_validation to billing_settlement_guard;

-- Existing triggers are installed by the deployment account. Application logins
-- need no EXECUTE privilege to fire them and cannot attach these functions elsewhere.
revoke all on function billing.protect_last_admin(), billing.enforce_monthly_settlement()
    from public, billing_bff, billing_batch, billing_ingestion;
alter function billing.protect_last_admin() owner to billing_membership_guard;
alter function billing.enforce_monthly_settlement() owner to billing_settlement_guard;
alter function billing.protect_last_admin() security definer;
alter function billing.enforce_monthly_settlement() security definer;
alter function billing.protect_last_admin() set search_path = '';
alter function billing.enforce_monthly_settlement() set search_path = '';
alter function billing.protect_last_admin() set row_security = on;
alter function billing.enforce_monthly_settlement() set row_security = on;

commit;
