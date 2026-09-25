-- Run with ON_ERROR_STOP against a disposable database after the occupancy migration.
begin;
insert into billing.billing_account values ('occ-test-x', 'X', now()) on conflict do nothing;
insert into billing.occupancy_stream(source) values ('urn:test:constraints');
insert into billing.occupancy_interval(source, occupancy_id, billing_account_id, started_at, ended_at, changed_version)
values ('urn:test:constraints','00000000-0000-0000-0000-000000000001','occ-test-x','2026-09-01 10:00Z','2026-09-01 10:01Z',1);
do $$ begin
    begin
        insert into billing.occupancy_interval values ('urn:test:constraints','00000000-0000-0000-0000-000000000002','occ-test-x','2026-09-01 10:00:30Z',null,2);
        raise exception 'overlap accepted';
    exception when exclusion_violation then null; end;
end $$;
set local role billing_occupancy;
select billing_account_id from billing.billing_account where billing_account_id='occ-test-x';
do $$ begin
    begin perform billing_account_name from billing.billing_account; raise exception 'name exposed';
    exception when insufficient_privilege then null; end;
    begin update billing.billing_account set billing_account_name='bad'; raise exception 'account writable';
    exception when insufficient_privilege then null; end;
    begin delete from billing.occupancy_receipt; raise exception 'receipt deletable';
    exception when insufficient_privilege then null; end;
    begin create table billing.occupancy_forbidden(id int); raise exception 'DDL allowed';
    exception when insufficient_privilege then null; end;
    begin perform * from billing.billing_membership; raise exception 'membership exposed';
    exception when insufficient_privilege then null; end;
end $$;
rollback;
