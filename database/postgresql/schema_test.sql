\set ON_ERROR_STOP on

insert into billing.billing_account (billing_account_id, billing_account_name) values
    ('tenant-001', 'Tenant One'),
    ('tenant-002', 'Tenant Two');

insert into billing.app_user (user_id, email, display_name, password_hash) values
    ('0198a25d-63c7-7001-8000-000000000001', 'admin1@example.com', 'Admin One', 'test-hash'),
    ('0198a25d-63c7-7002-8000-000000000002', 'viewer1@example.com', 'Viewer One', 'test-hash'),
    ('0198a25d-63c7-7003-8000-000000000003', 'admin2@example.com', 'Admin Two', 'test-hash');

insert into billing.billing_membership (billing_account_id, user_id, role) values
    ('tenant-001', '0198a25d-63c7-7001-8000-000000000001', 'BILLING_ACCOUNT_ADMIN'),
    ('tenant-001', '0198a25d-63c7-7002-8000-000000000002', 'BILLING_ACCOUNT_VIEWER'),
    ('tenant-002', '0198a25d-63c7-7003-8000-000000000003', 'BILLING_ACCOUNT_ADMIN');

do $$
begin
    begin
        update billing.billing_membership
           set role = 'BILLING_ACCOUNT_VIEWER'
         where billing_account_id = 'tenant-001'
           and user_id = '0198a25d-63c7-7001-8000-000000000001';
        raise exception 'last admin guard did not reject update';
    exception
        when check_violation then null;
    end;
end;
$$;

insert into billing.pricing_sku (
    sku_id, service_category, sku_meter, consumed_unit
) values
    ('compute-small-linux', 'Compute', 'Compute Usage', 'Second'),
    ('compute-medium-linux', 'Compute', 'Compute Usage', 'Second');

insert into billing.price_rate (
    price_rate_id, sku_id, valid_from, valid_to, unit_price
) values (
    '0198a25d-63c7-7101-8000-000000000101',
    'compute-small-linux',
    '2026-08-01T00:00:00Z',
    null,
    0.001000000000000000
);

do $$
begin
    begin
        insert into billing.price_rate (
            price_rate_id, sku_id, valid_from, valid_to, unit_price
        ) values (
            '0198a25d-63c7-7102-8000-000000000102',
            'compute-small-linux',
            '2026-08-15T00:00:00Z',
            null,
            0.002000000000000000
        );
        raise exception 'overlapping price rate was accepted';
    exception
        when exclusion_violation then null;
    end;
end;
$$;

update billing.price_rate
   set valid_to = '2026-09-01T00:00:00Z'
 where price_rate_id = '0198a25d-63c7-7101-8000-000000000101';

insert into billing.price_rate (
    price_rate_id, sku_id, valid_from, valid_to, unit_price
) values (
    '0198a25d-63c7-7103-8000-000000000103',
    'compute-small-linux',
    '2026-09-01T00:00:00Z',
    null,
    0.002000000000000000
);

do $$
declare
    exported_count integer;
begin
    select count(*) into exported_count
      from billing.clickhouse_price_rate_export;
    if exported_count <> 2 then
        raise exception 'price export contains % rows', exported_count;
    end if;
end;
$$;

insert into billing.settlement_job (
    billing_account_id, billing_month
) values (
    'tenant-001', '2026-07-01'
);

insert into billing.settlement_attempt (
    run_id, billing_account_id, billing_month, attempt_number, status
) values (
    '0198a25d-63c7-7201-8000-000000000201',
    'tenant-001', '2026-07-01', 1, 'RUNNING'
);

do $$
begin
    begin
        insert into billing.settlement_attempt (
            run_id, billing_account_id, billing_month, attempt_number, status
        ) values (
            '0198a25d-63c7-7202-8000-000000000202',
            'tenant-001', '2026-07-01', 2, 'RUNNING'
        );
        raise exception 'second running attempt was accepted';
    exception
        when unique_violation then null;
    end;
end;
$$;

update billing.settlement_attempt
   set status = 'VALIDATED', finished_at = now()
 where run_id = '0198a25d-63c7-7201-8000-000000000201';

do $$
begin
    begin
        update billing.settlement_attempt
           set status = 'FAILED', error_code = 'RETRY', finished_at = now()
         where run_id = '0198a25d-63c7-7201-8000-000000000201';
        raise exception 'completed attempt was overwritten';
    exception
        when object_not_in_prerequisite_state then null;
    end;
end;
$$;

insert into billing.settlement_validation (
    run_id, billing_account_id, billing_month,
    expected_cost, recalculated_cost, input_data_as_of
) values (
    '0198a25d-63c7-7201-8000-000000000201',
    'tenant-001', '2026-07-01',
    100.000000, 100.000000, '2026-08-01T00:00:00Z'
);

begin;
insert into billing.monthly_settlement (
    billing_account_id, billing_month, run_id, billed_cost
) values (
    'tenant-001', '2026-07-01',
    '0198a25d-63c7-7201-8000-000000000201', 100.000000
);
update billing.settlement_job
   set status = 'FINALIZED', finalized_at = now()
 where billing_account_id = 'tenant-001'
   and billing_month = '2026-07-01';
commit;

do $$
begin
    begin
        update billing.monthly_settlement
           set billed_cost = 101.000000
         where billing_account_id = 'tenant-001'
           and billing_month = '2026-07-01';
        raise exception 'immutable settlement was updated';
    exception
        when object_not_in_prerequisite_state then null;
    end;
end;
$$;

insert into billing.settlement_job (
    billing_account_id, billing_month
) values (
    'tenant-002', '2026-07-01'
);

do $$
begin
    begin
        update billing.settlement_job
           set status = 'FINALIZED', finalized_at = now()
         where billing_account_id = 'tenant-002'
           and billing_month = '2026-07-01';
        raise exception 'job without settlement was finalized';
    exception
        when check_violation then null;
    end;
end;
$$;

insert into billing.settlement_attempt (
    run_id, billing_account_id, billing_month, attempt_number,
    status, finished_at
) values (
    '0198a25d-63c7-7203-8000-000000000203',
    'tenant-002', '2026-07-01', 1,
    'VALIDATED', now()
);

insert into billing.settlement_validation (
    run_id, billing_account_id, billing_month,
    expected_cost, recalculated_cost, input_data_as_of
) values (
    '0198a25d-63c7-7203-8000-000000000203',
    'tenant-002', '2026-07-01',
    100.000000, 101.000000, '2026-08-01T00:00:00Z'
);

do $$
begin
    begin
        insert into billing.monthly_settlement (
            billing_account_id, billing_month, run_id, billed_cost
        ) values (
            'tenant-002', '2026-07-01',
            '0198a25d-63c7-7203-8000-000000000203', 101.000000
        );
        raise exception 'non-zero validation difference was finalized';
    exception
        when check_violation then null;
    end;
end;
$$;

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'billing_schema_test') then
        create role billing_schema_test nologin;
    end if;
end;
$$;
grant usage on schema billing to billing_schema_test;
grant select on billing.billing_account, billing.billing_membership to billing_schema_test;

begin;
set local role billing_schema_test;
select set_config('app.current_user_id', '0198a25d-63c7-7002-8000-000000000002', true);

do $$
declare
    visible_count integer;
begin
    select count(*) into visible_count from billing.billing_membership;
    if visible_count <> 1 then
        raise exception 'pre-tenant membership lookup exposed % rows', visible_count;
    end if;
end;
$$;
rollback;

begin;
set local role billing_schema_test;
select set_config('app.current_user_id', '0198a25d-63c7-7002-8000-000000000002', true);
select set_config('app.billing_account_id', 'tenant-001', true);

do $$
declare
    account_count integer;
    member_count integer;
begin
    select count(*) into account_count from billing.billing_account;
    select count(*) into member_count from billing.billing_membership;
    if account_count <> 1 or member_count <> 2 then
        raise exception 'tenant scope mismatch: accounts %, members %', account_count, member_count;
    end if;
end;
$$;
rollback;

begin;
set local role billing_schema_test;
do $$
declare
    visible_count integer;
begin
    select count(*) into visible_count from billing.billing_account;
    if visible_count <> 0 then
        raise exception 'SET LOCAL tenant context leaked across transactions';
    end if;
end;
$$;
rollback;

select 'postgresql schema tests passed' as result;
