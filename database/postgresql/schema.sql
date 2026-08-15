create extension if not exists btree_gist;

create schema if not exists billing;

create table billing.billing_account (
    billing_account_id text primary key,
    billing_account_name text not null,
    created_at timestamptz not null default now(),
    constraint billing_account_id_not_blank check (btrim(billing_account_id) <> ''),
    constraint billing_account_name_not_blank check (btrim(billing_account_name) <> '')
);

create table billing.app_user (
    user_id uuid primary key,
    email text not null,
    display_name text not null,
    password_hash text not null,
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    constraint app_user_email_normalized check (email = lower(btrim(email))),
    constraint app_user_display_name_not_blank check (btrim(display_name) <> ''),
    constraint app_user_password_hash_not_blank check (btrim(password_hash) <> ''),
    constraint app_user_status_valid check (status in ('ACTIVE', 'BLOCKED'))
);

create unique index app_user_email_uq on billing.app_user (email);

create table billing.billing_membership (
    billing_account_id text not null,
    user_id uuid not null,
    role text not null,
    created_at timestamptz not null default now(),
    ended_at timestamptz,
    primary key (billing_account_id, user_id),
    constraint billing_membership_account_fk foreign key (billing_account_id)
        references billing.billing_account (billing_account_id),
    constraint billing_membership_user_fk foreign key (user_id)
        references billing.app_user (user_id),
    constraint billing_membership_role_valid check (
        role in ('BILLING_ACCOUNT_VIEWER', 'BILLING_ACCOUNT_ADMIN')
    ),
    constraint billing_membership_period_valid check (
        ended_at is null or ended_at >= created_at
    )
);

create unique index billing_membership_active_user_uq
    on billing.billing_membership (user_id)
    where ended_at is null;
create index billing_membership_user_idx
    on billing.billing_membership (user_id);

-- Spring Session JDBC 4.1 PostgreSQL schema, placed in the billing schema.
create table billing.spring_session (
    primary_id char(36) not null,
    session_id char(36) not null,
    creation_time bigint not null,
    last_access_time bigint not null,
    max_inactive_interval integer not null,
    expiry_time bigint not null,
    principal_name varchar(100),
    constraint spring_session_pk primary key (primary_id)
);

create unique index spring_session_ix1 on billing.spring_session (session_id);
create index spring_session_ix2 on billing.spring_session (expiry_time);
create index spring_session_ix3 on billing.spring_session (principal_name);

create table billing.spring_session_attributes (
    session_primary_id char(36) not null,
    attribute_name varchar(200) not null,
    attribute_bytes bytea not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id)
        references billing.spring_session(primary_id) on delete cascade
);

create or replace function billing.protect_last_admin()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    removes_admin boolean;
begin
    if tg_op = 'DELETE' then
        removes_admin := true;
    else
        removes_admin := new.ended_at is not null
            or new.role <> 'BILLING_ACCOUNT_ADMIN';
    end if;

    if old.ended_at is null
       and old.role = 'BILLING_ACCOUNT_ADMIN'
       and removes_admin then
        perform 1
        from billing.billing_account
        where billing_account_id = old.billing_account_id
        for update;

        if not exists (
            select 1
            from billing.billing_membership
            where billing_account_id = old.billing_account_id
              and user_id <> old.user_id
              and role = 'BILLING_ACCOUNT_ADMIN'
              and ended_at is null
        ) then
            raise exception 'billing account must retain an active admin'
                using errcode = '23514';
        end if;
    end if;

    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create trigger billing_membership_last_admin_guard
before update of role, ended_at or delete on billing.billing_membership
for each row execute function billing.protect_last_admin();

create table billing.security_audit_event (
    audit_event_id uuid primary key,
    billing_account_id text not null,
    actor_user_id uuid not null,
    target_user_id uuid,
    event_type text not null,
    previous_role text,
    new_role text,
    denied_action text,
    reason_code text,
    occurred_at timestamptz not null default now(),
    constraint security_audit_account_fk foreign key (billing_account_id)
        references billing.billing_account (billing_account_id),
    constraint security_audit_actor_membership_fk foreign key (
        billing_account_id, actor_user_id
    ) references billing.billing_membership (billing_account_id, user_id),
    constraint security_audit_target_membership_fk foreign key (
        billing_account_id, target_user_id
    ) references billing.billing_membership (billing_account_id, user_id),
    constraint security_audit_shape_valid check (
        (
            event_type = 'ROLE_CHANGED'
            and target_user_id is not null
            and previous_role is distinct from new_role
            and (previous_role is not null or new_role is not null)
            and denied_action is null
            and reason_code is null
        )
        or
        (
            event_type = 'ACCESS_DENIED'
            and target_user_id is null
            and previous_role is null
            and new_role is null
            and denied_action is not null
            and reason_code is not null
            and btrim(denied_action) <> ''
            and btrim(reason_code) <> ''
        )
    ),
    constraint security_audit_previous_role_valid check (
        previous_role is null
        or previous_role in ('BILLING_ACCOUNT_VIEWER', 'BILLING_ACCOUNT_ADMIN')
    ),
    constraint security_audit_new_role_valid check (
        new_role is null
        or new_role in ('BILLING_ACCOUNT_VIEWER', 'BILLING_ACCOUNT_ADMIN')
    )
);

create index security_audit_account_time_idx
    on billing.security_audit_event (billing_account_id, occurred_at desc);
create index security_audit_actor_idx
    on billing.security_audit_event (billing_account_id, actor_user_id);
create index security_audit_target_idx
    on billing.security_audit_event (billing_account_id, target_user_id)
    where target_user_id is not null;

create table billing.usage_producer (
    billing_account_id text not null,
    producer_id text not null,
    source text not null,
    status text not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    primary key (billing_account_id, producer_id),
    constraint usage_producer_account_fk foreign key (billing_account_id)
        references billing.billing_account (billing_account_id),
    constraint usage_producer_id_not_blank check (btrim(producer_id) <> ''),
    constraint usage_producer_source_not_blank check (btrim(source) <> ''),
    constraint usage_producer_source_matches_id check (
        source = 'urn:cloud-usage:meter:' || producer_id
    ),
    constraint usage_producer_status_valid check (status in ('ACTIVE', 'REVOKED')),
    unique (source)
);

create table billing.producer_credential (
    credential_id uuid primary key,
    billing_account_id text not null,
    producer_id text not null,
    secret_hash text not null,
    valid_from timestamptz not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    last_used_at timestamptz,
    created_at timestamptz not null default now(),
    constraint producer_credential_producer_fk foreign key (billing_account_id, producer_id)
        references billing.usage_producer (billing_account_id, producer_id),
    constraint producer_credential_secret_hash_not_blank check (btrim(secret_hash) <> ''),
    constraint producer_credential_period_valid check (expires_at > valid_from),
    constraint producer_credential_revoked_valid check (
        revoked_at is null or revoked_at >= valid_from
    )
);

create index producer_credential_producer_idx
    on billing.producer_credential (billing_account_id, producer_id);

create table billing.event_rejection (
    rejection_id uuid primary key,
    billing_account_id text,
    producer_id text,
    event_source text,
    event_id text,
    rejection_stage text not null,
    reason_code text not null,
    received_at timestamptz not null default now(),
    constraint event_rejection_account_fk foreign key (billing_account_id)
        references billing.billing_account (billing_account_id),
    constraint event_rejection_producer_fk foreign key (billing_account_id, producer_id)
        references billing.usage_producer (billing_account_id, producer_id),
    constraint event_rejection_producer_scope_valid check (
        producer_id is null or billing_account_id is not null
    ),
    constraint event_rejection_stage_valid check (
        rejection_stage in ('AUTHENTICATION', 'ENVELOPE', 'SCHEMA', 'SEMANTIC')
    ),
    constraint event_rejection_reason_not_blank check (btrim(reason_code) <> '')
);

create index event_rejection_received_at_idx
    on billing.event_rejection (received_at desc);
create index event_rejection_account_time_idx
    on billing.event_rejection (billing_account_id, received_at desc)
    where billing_account_id is not null;
create index event_rejection_producer_idx
    on billing.event_rejection (billing_account_id, producer_id)
    where producer_id is not null;

create table billing.pricing_sku (
    sku_id text primary key,
    service_category text not null,
    sku_meter text not null,
    consumed_unit text not null,
    active boolean not null default true,
    constraint pricing_sku_id_not_blank check (btrim(sku_id) <> ''),
    constraint pricing_sku_service_valid check (
        service_category in ('Compute', 'Storage', 'Networking')
    ),
    constraint pricing_sku_meter_not_blank check (btrim(sku_meter) <> ''),
    constraint pricing_sku_unit_valid check (
        consumed_unit in ('Second', 'GiB-Second', 'Byte')
    )
);

create table billing.price_rate (
    price_rate_id uuid primary key,
    sku_id text not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    unit_price numeric(30,18) not null,
    currency char(3) not null default 'KRW',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint price_rate_sku_fk foreign key (sku_id)
        references billing.pricing_sku (sku_id),
    constraint price_rate_period_valid check (valid_to is null or valid_to > valid_from),
    constraint price_rate_nonnegative check (unit_price >= 0),
    constraint price_rate_currency_krw check (currency = 'KRW'),
    constraint price_rate_no_overlap exclude using gist (
        sku_id with =,
        tstzrange(valid_from, valid_to, '[)') with &&
    )
);

create index price_rate_lookup_idx
    on billing.price_rate (sku_id, valid_from desc);

create or replace function billing.protect_price_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'price rate is immutable' using errcode = '55000';
    end if;

    if new.price_rate_id <> old.price_rate_id
       or new.sku_id <> old.sku_id
       or new.valid_from <> old.valid_from
       or new.unit_price <> old.unit_price
       or new.currency <> old.currency
       or new.created_at <> old.created_at
       or old.valid_to is not null
       or new.valid_to is null then
        raise exception 'only an open price rate may be closed once'
            using errcode = '55000';
    end if;
    new.updated_at := clock_timestamp();
    return new;
end;
$$;

create trigger price_rate_history_guard
before update or delete on billing.price_rate
for each row execute function billing.protect_price_history();

create view billing.clickhouse_price_rate_export as
select
    r.price_rate_id,
    r.sku_id,
    s.service_category,
    s.sku_meter,
    s.consumed_unit,
    r.valid_from,
    r.valid_to,
    r.unit_price,
    r.currency,
    floor(extract(epoch from r.updated_at) * 1000000)::bigint as sync_version
from billing.price_rate r
join billing.pricing_sku s on s.sku_id = r.sku_id;

create table billing.settlement_job (
    billing_account_id text not null,
    billing_month date not null,
    status text not null default 'PENDING',
    next_attempt_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    finalized_at timestamptz,
    primary key (billing_account_id, billing_month),
    constraint settlement_job_account_fk foreign key (billing_account_id)
        references billing.billing_account (billing_account_id),
    constraint settlement_job_month_start check (
        billing_month = date_trunc('month', billing_month)::date
    ),
    constraint settlement_job_status_valid check (status in ('PENDING', 'FINALIZED')),
    constraint settlement_job_finalized_shape check (
        (status = 'PENDING' and finalized_at is null)
        or (status = 'FINALIZED' and finalized_at is not null)
    )
);

create index settlement_job_pending_idx
    on billing.settlement_job (next_attempt_at, billing_account_id, billing_month)
    where status = 'PENDING';

create table billing.settlement_attempt (
    run_id uuid primary key,
    billing_account_id text not null,
    billing_month date not null,
    attempt_number integer not null,
    status text not null,
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    error_code text,
    error_message text,
    constraint settlement_attempt_job_fk foreign key (billing_account_id, billing_month)
        references billing.settlement_job (billing_account_id, billing_month),
    constraint settlement_attempt_number_positive check (attempt_number > 0),
    constraint settlement_attempt_status_valid check (
        status in ('RUNNING', 'FAILED', 'VALIDATED')
    ),
    constraint settlement_attempt_shape_valid check (
        (status = 'RUNNING' and finished_at is null and error_code is null and error_message is null)
        or (
            status = 'FAILED'
            and finished_at is not null
            and error_code is not null
            and btrim(error_code) <> ''
        )
        or (status = 'VALIDATED' and finished_at is not null and error_code is null and error_message is null)
    ),
    unique (billing_account_id, billing_month, attempt_number),
    unique (billing_account_id, billing_month, run_id)
);

create unique index settlement_attempt_running_uq
    on billing.settlement_attempt (billing_account_id, billing_month)
    where status = 'RUNNING';
create index settlement_attempt_history_idx
    on billing.settlement_attempt (billing_account_id, billing_month, started_at desc);

create or replace function billing.protect_settlement_attempt_history()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'settlement attempt history is immutable'
            using errcode = '55000';
    end if;

    if old.status <> 'RUNNING'
       or new.run_id <> old.run_id
       or new.billing_account_id <> old.billing_account_id
       or new.billing_month <> old.billing_month
       or new.attempt_number <> old.attempt_number
       or new.started_at <> old.started_at
       or new.status = 'RUNNING' then
        raise exception 'settlement attempt history is immutable'
            using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger settlement_attempt_history_guard
before update or delete on billing.settlement_attempt
for each row execute function billing.protect_settlement_attempt_history();

create table billing.settlement_validation (
    run_id uuid primary key,
    billing_account_id text not null,
    billing_month date not null,
    expected_cost numeric(30,6) not null,
    recalculated_cost numeric(30,6) not null,
    difference numeric(30,6) generated always as (recalculated_cost - expected_cost) stored,
    input_data_as_of timestamptz not null,
    validated_at timestamptz not null default now(),
    constraint settlement_validation_attempt_fk foreign key (
        billing_account_id, billing_month, run_id
    ) references billing.settlement_attempt (billing_account_id, billing_month, run_id),
    constraint settlement_validation_cost_nonnegative check (
        expected_cost >= 0 and recalculated_cost >= 0
    ),
    unique (billing_account_id, billing_month, run_id)
);

create table billing.monthly_settlement (
    billing_account_id text not null,
    billing_month date not null,
    run_id uuid not null,
    billed_cost numeric(30,6) not null,
    currency char(3) not null default 'KRW',
    finalized_at timestamptz not null default now(),
    primary key (billing_account_id, billing_month),
    constraint monthly_settlement_validation_fk foreign key (
        billing_account_id, billing_month, run_id
    ) references billing.settlement_validation (billing_account_id, billing_month, run_id),
    constraint monthly_settlement_cost_nonnegative check (billed_cost >= 0),
    constraint monthly_settlement_currency_krw check (currency = 'KRW'),
    unique (run_id)
);

create or replace function billing.enforce_monthly_settlement()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    attempt_status text;
    validated_cost numeric(30,6);
    validation_difference numeric(30,6);
begin
    select a.status, v.recalculated_cost, v.difference
      into attempt_status, validated_cost, validation_difference
      from billing.settlement_attempt a
      join billing.settlement_validation v
        on v.billing_account_id = a.billing_account_id
       and v.billing_month = a.billing_month
       and v.run_id = a.run_id
     where a.billing_account_id = new.billing_account_id
       and a.billing_month = new.billing_month
       and a.run_id = new.run_id
     for key share of a, v;

    if not found
       or attempt_status <> 'VALIDATED'
       or validation_difference <> 0
       or validated_cost <> new.billed_cost then
        raise exception 'monthly settlement requires a matching zero-difference validated attempt'
            using errcode = '23514';
    end if;

    return new;
end;
$$;

create trigger monthly_settlement_validation_guard
before insert on billing.monthly_settlement
for each row execute function billing.enforce_monthly_settlement();

create or replace function billing.reject_immutable_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    raise exception '% is immutable', tg_table_name using errcode = '55000';
end;
$$;

create trigger security_audit_event_immutable
before update or delete on billing.security_audit_event
for each row execute function billing.reject_immutable_mutation();

create trigger settlement_validation_immutable
before update or delete on billing.settlement_validation
for each row execute function billing.reject_immutable_mutation();

create trigger monthly_settlement_immutable
before update or delete on billing.monthly_settlement
for each row execute function billing.reject_immutable_mutation();

create or replace function billing.require_finalized_settlement_pair()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    job_status text;
    settlement_exists boolean;
begin
    if tg_table_name = 'settlement_job' then
        if old.status = 'FINALIZED' then
            raise exception 'finalized settlement job is immutable'
                using errcode = '55000';
        end if;

        if new.status = 'FINALIZED' and old.status <> 'FINALIZED' then
            select exists (
                select 1
                from billing.monthly_settlement
                where billing_account_id = new.billing_account_id
                  and billing_month = new.billing_month
            ) into settlement_exists;

            if not settlement_exists then
                raise exception 'finalized job requires a monthly settlement'
                    using errcode = '23514';
            end if;
        end if;
        return new;
    end if;

    select status
      into job_status
      from billing.settlement_job
     where billing_account_id = new.billing_account_id
       and billing_month = new.billing_month;

    if job_status <> 'FINALIZED' then
        raise exception 'monthly settlement requires a finalized job'
            using errcode = '23514';
    end if;
    return null;
end;
$$;

create trigger settlement_job_finalization_guard
before update on billing.settlement_job
for each row execute function billing.require_finalized_settlement_pair();

create constraint trigger monthly_settlement_job_pair_guard
after insert on billing.monthly_settlement
deferrable initially deferred
for each row execute function billing.require_finalized_settlement_pair();

create or replace function billing.current_user_id()
returns uuid
language sql
stable
set search_path = ''
as $$
    select nullif(pg_catalog.current_setting('app.current_user_id', true), '')::uuid
$$;

create or replace function billing.current_billing_account_id()
returns text
language sql
stable
set search_path = ''
as $$
    select nullif(pg_catalog.current_setting('app.billing_account_id', true), '')
$$;

alter table billing.billing_account enable row level security;
alter table billing.billing_account force row level security;
alter table billing.billing_membership enable row level security;
alter table billing.billing_membership force row level security;
alter table billing.security_audit_event enable row level security;
alter table billing.security_audit_event force row level security;
alter table billing.settlement_job enable row level security;
alter table billing.settlement_job force row level security;
alter table billing.settlement_attempt enable row level security;
alter table billing.settlement_attempt force row level security;
alter table billing.settlement_validation enable row level security;
alter table billing.settlement_validation force row level security;
alter table billing.monthly_settlement enable row level security;
alter table billing.monthly_settlement force row level security;

create policy billing_account_tenant_policy on billing.billing_account
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy billing_membership_select_policy on billing.billing_membership
    for select
    using (
        user_id = (select billing.current_user_id())
        or billing_account_id = (select billing.current_billing_account_id())
    );

create policy billing_membership_write_policy on billing.billing_membership
    for all
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy security_audit_tenant_policy on billing.security_audit_event
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy settlement_job_tenant_policy on billing.settlement_job
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy settlement_attempt_tenant_policy on billing.settlement_attempt
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy settlement_validation_tenant_policy on billing.settlement_validation
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));

create policy monthly_settlement_tenant_policy on billing.monthly_settlement
    using (billing_account_id = (select billing.current_billing_account_id()))
    with check (billing_account_id = (select billing.current_billing_account_id()));
