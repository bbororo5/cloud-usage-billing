-- Additive, repeatable migration. Execute as deployment owner, never the worker login.
begin;
create extension if not exists btree_gist;
create table if not exists billing.occupancy_stream (
    source text primary key check (btrim(source) <> ''), subject text,
    initialized boolean not null default false, baseline_at timestamptz(3), confirmed_through timestamptz(3),
    last_applied_sequence bigint not null default 0 check(last_applied_sequence >= 0),
    version bigint not null default 0 check(version >= 0),
    next_retry_at timestamptz not null default now(), last_attempt_at timestamptz not null default '-infinity',
    attempts integer not null default 0, waiting_since timestamptz,
    check (not initialized or (subject is not null and baseline_at is not null and confirmed_through >= baseline_at))
);
create table if not exists billing.occupancy_event (
    source text not null references billing.occupancy_stream(source), id uuid not null,
    sequence bigint not null check(sequence > 0), kind text not null,
    content jsonb not null, state text not null default 'RECEIVED' check(state in ('RECEIVED','WAITING','APPLIED','ERROR')),
    reason text, received_at timestamptz not null default now(),
    primary key(source,id), unique(source,sequence)
);
create table if not exists billing.occupancy_receipt (
    topic text not null, partition integer not null, "offset" bigint not null,
    key_bytes bytea, headers jsonb not null, raw_bytes bytea, received_at timestamptz not null default now(),
    parse_result text not null, source text, event_id uuid,
    primary key(topic,partition,"offset")
);
create table if not exists billing.occupancy_interval (
    source text not null references billing.occupancy_stream(source), occupancy_id uuid not null,
    billing_account_id text not null references billing.billing_account(billing_account_id),
    started_at timestamptz(3) not null, ended_at timestamptz(3), changed_version bigint not null check(changed_version > 0),
    primary key(source,occupancy_id), check(ended_at is null or ended_at > started_at),
    exclude using gist(source with =, tstzrange(started_at,ended_at,'[)') with &&)
);
create table if not exists billing.occupancy_issue (
    issue_id uuid primary key, dedup_key text not null unique,
    source text references billing.occupancy_stream(source), event_id uuid,
    scope_from timestamptz(3), scope_to timestamptz(3), reason text not null,
    blocking boolean not null default true, retryable boolean not null default false,
    status text not null default 'OPEN' check(status in ('OPEN','RESOLVED')),
    topic text, partition integer, "offset" bigint,
    created_at timestamptz not null default now(),
    alert_state text not null default 'PENDING' check(alert_state in ('PENDING','SENT')),
    alert_attempts integer not null default 0, next_alert_at timestamptz not null default now(),
    foreign key(topic,partition,"offset") references billing.occupancy_receipt(topic,partition,"offset"),
    check(scope_to is null or scope_from is null or scope_from < scope_to)
);
create table if not exists billing.occupancy_issue_action (
    action_id uuid primary key, request_id uuid unique, issue_id uuid not null references billing.occupancy_issue(issue_id),
    operator text not null, reason text not null, result text not null, created_at timestamptz not null default now()
);
create index if not exists occupancy_event_pending on billing.occupancy_event(source,sequence) where state <> 'APPLIED';
create index if not exists occupancy_stream_due on billing.occupancy_stream(next_retry_at,last_attempt_at);
create index if not exists occupancy_issue_open on billing.occupancy_issue(source) where status='OPEN';
create index if not exists occupancy_issue_alert on billing.occupancy_issue(next_alert_at) where alert_state='PENDING';
do $$ begin
    if not exists(select 1 from pg_roles where rolname='billing_occupancy') then
        create role billing_occupancy login password 'local-dev-only'
            nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
    end if;
    if exists(select 1 from pg_roles where rolname='billing_occupancy' and
        (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
       or exists(select 1 from pg_roles where rolname <> 'billing_occupancy' and pg_has_role('billing_occupancy',oid,'MEMBER')) then
        raise exception 'unsafe occupancy role';
    end if;
end $$;
grant usage on schema billing to billing_occupancy;
grant select, insert on billing.occupancy_receipt, billing.occupancy_issue_action to billing_occupancy;
grant select, insert, update on billing.occupancy_event, billing.occupancy_stream,
    billing.occupancy_interval, billing.occupancy_issue to billing_occupancy;
-- RLS remains enabled; this role can read only IDs, never customer names or other tables.
grant select(billing_account_id) on billing.billing_account to billing_occupancy;
drop policy if exists occupancy_account_ids on billing.billing_account;
create policy occupancy_account_ids on billing.billing_account for select to billing_occupancy using(true);
commit;
