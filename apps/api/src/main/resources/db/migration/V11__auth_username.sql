-- Username-keyed authentication and per-user clinic membership lookup.
--
-- BREAKING: adds a NOT NULL unique username column to app_user and makes
-- email optional. Email was the legacy auth key; username is the new primary
-- auth key for login. Email is retained as optional for future use (password
-- reset emails, invites, etc.) in later phases. The unique constraint on
-- email is replaced with a partial unique index that only applies to non-null
-- values, since a truly optional column should allow multiple NULLs.
--
-- Two new SECURITY DEFINER functions support the login flow before a tenant
-- (clinic_id) is known:
--
-- 1. app_user_credentials_lookup_by_username: returns (id, password_hash, status)
--    for a given username. Filters by username instead of email.
--
-- 2. app_user_memberships_lookup: takes a user_id and returns all active
--    memberships (clinic_id, clinic_name, role_code). Both functions bypass
--    row-level security via SECURITY DEFINER, so they are the ONLY intended
--    paths for app_rw to query app_user or membership before app.clinic_id
--    is bound. Direct SELECT on those tables still returns zero rows until
--    a tenant is set (see V9 RLS policies).

alter table app_user add column username citext not null unique;
alter table app_user alter column email drop not null;
alter table app_user drop constraint app_user_email_key;
create unique index idx_app_user_email_when_not_null on app_user (email) where email is not null;

grant select (username) on app_user to app_rw;

create function app_user_credentials_lookup_by_username(p_username citext)
returns table (id uuid, password_hash text, status text)
language sql
security definer
stable
set search_path = pg_catalog, public, pg_temp
as $$
    select id, password_hash, status from public.app_user where username = p_username;
$$;

revoke all on function app_user_credentials_lookup_by_username(citext) from public;
grant execute on function app_user_credentials_lookup_by_username(citext) to app_rw;

create function app_user_memberships_lookup(p_user_id uuid)
returns table (membership_id uuid, clinic_id uuid, clinic_name text, role_code text)
language sql
security definer
stable
set search_path = pg_catalog, public, pg_temp
as $$
    select m.id, m.clinic_id, c.name, r.code
    from public.membership m
    join public.clinic c on c.id = m.clinic_id
    join public.role r on r.id = m.role_id
    where m.user_id = p_user_id and m.status = 'active';
$$;

revoke all on function app_user_memberships_lookup(uuid) from public;
grant execute on function app_user_memberships_lookup(uuid) to app_rw;
