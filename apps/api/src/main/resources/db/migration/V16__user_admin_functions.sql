-- Admin functions for user management (slice 2c).
--
-- app_rw cannot INSERT/UPDATE app_user directly (V9 revoked DML), so these
-- SECURITY DEFINER functions are the sanctioned write path. Same hardening
-- as V11/V14: search_path = pg_catalog, public, pg_temp, schema-qualified,
-- revoke all from public, grant execute to app_rw.

create function create_clinic_user(
    p_clinic_id uuid,
    p_username text,
    p_full_name text,
    p_password_hash text,
    p_email text
)
returns uuid
language plpgsql
security definer
volatile
set search_path = pg_catalog, public, pg_temp
as $$
declare
    v_user_id uuid;
    v_membership_id uuid;
begin
    insert into public.app_user (clinic_id, username, full_name, password_hash, email, status)
    values (p_clinic_id, p_username::citext, p_full_name, p_password_hash, p_email::citext, 'active')
    returning id into v_user_id;

    insert into public.membership (clinic_id, user_id, role_id, status)
    select p_clinic_id, v_user_id, id, 'active'
    from public.role
    where code = 'receptionist'
    returning id into v_membership_id;

    if v_membership_id is null then
        raise exception 'receptionist role missing from public.role';
    end if;

    return v_user_id;
end;
$$;

revoke all on function create_clinic_user(uuid, text, text, text, text) from public;
grant execute on function create_clinic_user(uuid, text, text, text, text) to app_rw;

create function set_user_password(
    p_clinic_id uuid,
    p_user_id uuid,
    p_new_hash text
)
returns void
language plpgsql
security definer
volatile
set search_path = pg_catalog, public, pg_temp
as $$
begin
    update public.app_user
    set password_hash = p_new_hash
    where id = p_user_id and clinic_id = p_clinic_id;

    if not found then
        raise exception 'المستخدم غير موجود';
    end if;
end
$$;

revoke all on function set_user_password(uuid, uuid, text) from public;
grant execute on function set_user_password(uuid, uuid, text) to app_rw;

create function set_user_status(
    p_clinic_id uuid,
    p_user_id uuid,
    p_status text
)
returns void
language plpgsql
security definer
volatile
set search_path = pg_catalog, public, pg_temp
as $$
begin
    update public.app_user
    set status = p_status
    where id = p_user_id and clinic_id = p_clinic_id
      and p_status in ('active', 'suspended');

    if not found then
        raise exception 'المستخدم غير موجود أو الحالة غير صحيحة';
    end if;
end
$$;

revoke all on function set_user_status(uuid, uuid, text) from public;
grant execute on function set_user_status(uuid, uuid, text) to app_rw;
