-- Self-service clinic sign-up (UC-001, Phase 1b): provisions a new clinic
-- tenant plus its first owner user and owner membership in a single
-- transaction, all before app.clinic_id exists (the new clinic is not yet
-- known, and the app_user/clinic/membership tables are unreachable to app_rw
-- until a valid tenant is bound -- clinic and app_user are DML-revoked from
-- app_rw in V9, and membership is RLS-scoped so it matches zero rows before
-- a tenant exists).
--
-- V9 explicitly anticipated this -- "a SECURITY DEFINER function that itself
-- enforces cross-tenant rules (e.g. signup, membership creation)". This is the
-- ONLY door app_rw has to create a clinic + owner. It is hardened exactly like
-- the V11 pair: search_path pinned with pg_temp last, every relation
-- schema-qualified, execute revoked from public.
--
-- Unlike the V11 lookups (stable, read-only), this function is volatile: it
-- writes clinic, app_user and membership and returns all three generated ids
-- so the caller can bind the tenant and write the activity_log row without a
-- second lookup. Unique violations propagate as SQLSTATE 23505 with the
-- constraint name; Java maps them to per-field conflict errors.

create function signup_clinic_with_owner(
    p_clinic_name text,
    p_slug text,
    p_full_name text,
    p_username citext,
    p_email citext,
    p_password_hash text
)
returns table (user_id uuid, clinic_id uuid, membership_id uuid)
language plpgsql
security definer
volatile
set search_path = pg_catalog, public, pg_temp
as $$
declare
    v_clinic_id uuid;
    v_user_id uuid;
    v_membership_id uuid;
begin
    insert into public.clinic (name, slug)
    values (p_clinic_name, p_slug)
    returning id into v_clinic_id;

    insert into public.app_user (email, password_hash, full_name, username, status)
    values (p_email, p_password_hash, p_full_name, p_username, 'active')
    returning id into v_user_id;

    insert into public.membership (clinic_id, user_id, role_id, status)
    select v_clinic_id, v_user_id, id, 'active'
    from public.role
    where code = 'owner'
    returning id into v_membership_id;

    return query select v_user_id, v_clinic_id, v_membership_id;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;
