-- Additive/repeatable. Run as deployment owner; worker logins cannot change schema.
begin;
do $$ begin
  if not exists(select 1 from pg_roles where rolname='billing_attribution') then
    create role billing_attribution login password 'local-dev-only' nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
  end if;
  if not exists(select 1 from pg_roles where rolname='billing_history_guard') then
    create role billing_history_guard nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
  end if;
  if exists(select 1 from pg_roles where rolname in ('billing_attribution','billing_history_guard')
      and (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or exists(select 1 from pg_auth_members m join pg_roles r on r.oid=m.member
      where r.rolname in ('billing_attribution','billing_history_guard')) then
    raise exception 'unsafe attribution roles';
  end if;
end $$;
grant usage on schema billing to billing_attribution, billing_history_guard;
-- Locking SELECT needs UPDATE privilege, available only to this non-login function owner.
grant select, update on billing.occupancy_stream to billing_history_guard;
grant select on billing.occupancy_interval,billing.occupancy_issue to billing_history_guard;
grant select(billing_account_id,billing_month,status) on billing.settlement_job to billing_history_guard;
drop policy if exists history_guard_months on billing.settlement_job;
create policy history_guard_months on billing.settlement_job for select to billing_history_guard using(true);

create or replace function billing.lock_occupancy_history(p_source text,p_from timestamptz,p_to timestamptz)
returns jsonb language plpgsql security definer set search_path=pg_catalog,billing as $$
declare h billing.occupancy_stream%rowtype; conflict uuid; intervals jsonb;
begin
  if p_source is null or p_from is null or p_to is null or p_from>=p_to then raise exception 'invalid history query'; end if;
  select * into h from billing.occupancy_stream where source=p_source for update;
  if not found or not h.initialized then return jsonb_build_object('state','UNREGISTERED'); end if;
  select issue_id into conflict from billing.occupancy_issue where source=p_source and status='OPEN' and blocking
    and (scope_from is null or scope_from<p_to) and (scope_to is null or scope_to>p_from) order by created_at,issue_id limit 1;
  if conflict is not null then return jsonb_build_object('state','CONFLICT','issue',conflict); end if;
  if p_from<h.baseline_at then return jsonb_build_object('state','BEFORE_BASELINE'); end if;
  if p_to>h.confirmed_through then return jsonb_build_object('state','AWAITING_FACTS'); end if;
  select coalesce(jsonb_agg(jsonb_build_object('id',occupancy_id,'account',billing_account_id,'from',started_at,'to',ended_at)
    order by started_at,occupancy_id),'[]'::jsonb) into intervals from billing.occupancy_interval
    where source=p_source and started_at<p_to and (ended_at is null or ended_at>p_from);
  return jsonb_build_object('state','CONFIRMED','source',h.source,'subject',h.subject,'baseline',h.baseline_at,
    'through',h.confirmed_through,'version',h.version,'intervals',intervals);
end $$;
alter function billing.lock_occupancy_history(text,timestamptz,timestamptz) owner to billing_history_guard;
revoke all on function billing.lock_occupancy_history(text,timestamptz,timestamptz) from public;
grant execute on function billing.lock_occupancy_history(text,timestamptz,timestamptz) to billing_attribution;
-- Read-only closure gate; no finalization or monetary columns are exposed.
create or replace function billing.attribution_month_closed(p_account text,p_from timestamptz,p_to timestamptz)
returns boolean language sql security definer set search_path=pg_catalog,billing as $$
  select exists(select 1 from billing.settlement_job where billing_account_id=p_account and status='FINALIZED'
    and billing_month<=((p_to-interval '1 microsecond') at time zone 'UTC')::date
    and billing_month>=date_trunc('month',p_from at time zone 'UTC')::date)
$$;
alter function billing.attribution_month_closed(text,timestamptz,timestamptz) owner to billing_history_guard;
revoke all on function billing.attribution_month_closed(text,timestamptz,timestamptz) from public;
grant execute on function billing.attribution_month_closed(text,timestamptz,timestamptz) to billing_attribution;

create table if not exists billing.attribution_scan (
  id integer primary key check(id=1), generation bigint not null default 0,
  upper_source text, upper_id uuid, cursor_source text, cursor_id uuid,
  started_at timestamptz, completed_at timestamptz, rows_read bigint not null default 0, new_jobs bigint not null default 0,
  check((upper_source is null)=(upper_id is null)), check((cursor_source is null)=(cursor_id is null))
);
insert into billing.attribution_scan(id) values(1) on conflict do nothing;
create table if not exists billing.attribution_job (
  source text not null, event_id uuid not null, usage_json text not null,
  state text not null default 'PENDING' check(state in ('PENDING','WAITING','PREPARED','APPROVED','ERROR')),
  token uuid, lease_until timestamptz, due_at timestamptz not null default now(),
  prepared_revision uuid, approved_revision uuid, reason text,
  primary key(source,event_id), check((token is null)=(lease_until is null))
);
create table if not exists billing.attribution_attempt (
  revision uuid primary key, source text not null, event_id uuid not null,
  payload text not null, account text not null, occupancy uuid not null, history_version bigint not null,
  created_at timestamptz not null default now(),
  foreign key(source,event_id) references billing.attribution_job(source,event_id), unique(source,event_id,revision)
);
create table if not exists billing.attribution_approval (
  source text not null,event_id uuid not null,revision uuid not null, approved_at timestamptz not null default now(),
  primary key(source,event_id), foreign key(source,event_id,revision) references billing.attribution_attempt(source,event_id,revision)
);
create table if not exists billing.attribution_issue (
  source text not null,event_id uuid not null,reason text not null,
  status text not null default 'OPEN' check(status in ('OPEN','RESOLVED')),
  alert_sent boolean not null default false, alert_attempts integer not null default 0,
  next_alert_at timestamptz not null default now(), primary key(source,event_id),
  foreign key(source,event_id) references billing.attribution_job(source,event_id)
);
create table if not exists billing.attribution_action (
  request_id uuid primary key,source text not null,event_id uuid not null,operator text not null,reason text not null,
  result text not null,created_at timestamptz not null default now(),
  foreign key(source,event_id) references billing.attribution_job(source,event_id)
);
create index if not exists attribution_due on billing.attribution_job(due_at) where state not in ('APPROVED','ERROR');
grant select,insert,update on billing.attribution_scan,billing.attribution_job,billing.attribution_issue to billing_attribution;
grant select,insert on billing.attribution_attempt,billing.attribution_approval,billing.attribution_action to billing_attribution;
commit;
