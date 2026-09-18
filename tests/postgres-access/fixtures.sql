-- Only the disposable test database administrator loads these fixtures.
insert into billing.billing_account values
    ('a', 'Company A', now()), ('b', 'Company B', now());
insert into billing.app_user (user_id, email, display_name, password_hash)
select ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       'user' || n || '@example.test', 'User ' || n, 'test-only-hash'
from generate_series(1, 7) n;
insert into billing.billing_membership (billing_account_id, user_id, role)
select case when n <= 3 then 'a' else 'b' end,
       ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       case when n in (3, 6) then 'BILLING_ACCOUNT_VIEWER' else 'BILLING_ACCOUNT_ADMIN' end
from generate_series(1, 6) n;

insert into billing.pricing_sku (sku_id, service_category, sku_meter, consumed_unit)
values ('compute', 'Compute', 'Compute Usage', 'Second');
insert into billing.price_rate (price_rate_id, sku_id, valid_from, unit_price)
values ('00000000-0000-0000-0001-000000000001', 'compute', '2026-01-01Z', 0.001);

begin;
insert into billing.settlement_job (billing_account_id, billing_month)
select billing_account_id, '2026-07-01'::date from billing.billing_account;
insert into billing.settlement_attempt
    (run_id, billing_account_id, billing_month, attempt_number, status, finished_at)
select ('00000000-0000-0000-0002-00000000000' || n)::uuid,
       case n when 1 then 'a' else 'b' end, '2026-07-01', 1, 'VALIDATED', now()
from generate_series(1, 2) n;
insert into billing.settlement_validation
    (run_id, billing_account_id, billing_month, expected_cost, recalculated_cost, input_data_as_of)
select run_id, billing_account_id, billing_month, 100, 100, '2026-08-01Z'
from billing.settlement_attempt;
insert into billing.monthly_settlement (billing_account_id, billing_month, run_id, billed_cost)
select billing_account_id, billing_month, run_id, 100 from billing.settlement_attempt;
update billing.settlement_job set status = 'FINALIZED', finalized_at = now();
commit;
