\set ON_ERROR_STOP on

do $$
declare
    unsafe_roles integer;
begin
    select count(*) into unsafe_roles
      from pg_roles
     where rolname in ('billing_ingestion', 'billing_bff', 'billing_batch')
       and (rolsuper or rolcreaterole or rolcreatedb or rolbypassrls);

    if unsafe_roles <> 0 then
        raise exception 'application role has elevated PostgreSQL privileges';
    end if;

    if not has_table_privilege('billing_ingestion', 'billing.producer_credential', 'select')
       or has_table_privilege('billing_ingestion', 'billing.app_user', 'select') then
        raise exception 'ingestion role privilege boundary is invalid';
    end if;

    if not has_table_privilege('billing_bff', 'billing.spring_session', 'select,insert,update,delete')
       or has_table_privilege('billing_bff', 'billing.producer_credential', 'select') then
        raise exception 'BFF role privilege boundary is invalid';
    end if;
end;
$$;

select 'postgresql local role tests passed' as result;
