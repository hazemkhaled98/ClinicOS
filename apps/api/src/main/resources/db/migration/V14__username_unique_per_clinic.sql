-- Username unique per clinic (tenant-scoped login).
--
-- BREAKING: username becomes unique per clinic, not globally. One account
-- belongs to exactly one clinic (app_user.clinic_id is NOT NULL), so the same
-- person at two clinics has two accounts. Login now requires a clinic code
-- (clinic.slug) plus username plus password; the auth key is (clinic_slug,
-- username) via the new app_user_credentials_lookup_by_clinic_username
-- function.
--
-- The old username-keyed security definer function
-- (app_user_credentials_lookup_by_username) and the legacy email-keyed V9
-- function (app_user_credentials_lookup) are dropped. The V9 one was already
-- dead code (no Java caller) and a live pre-auth door on app_user.
--
-- Follow-up (not in scope): app_user now carries clinic_id, so row-level
-- security on it is finally possible -- V9 deliberately left the platform
-- tables unscoped.

-- 1. Add clinic_id, nullable for the backfill.
alter table app_user add column clinic_id uuid references clinic(id) on delete cascade;

-- 2. Backfill from membership (one row per user, oldest membership wins),
--    then delete users with no membership (unreachable; greenfield) and
--    enforce NOT NULL.
update app_user u
set clinic_id = m.clinic_id
from (
    select distinct on (user_id) user_id, clinic_id
    from membership
    order by user_id, created_at
) m
where u.id = m.user_id;

-- Loud, not silent: a genuinely empty/greenfield database deletes zero rows
-- here, so this never fires there. If it ever does fire against a database
-- that already had users, that's exactly the case this migration's
-- "unreachable; greenfield" assumption doesn't hold for, and it should stop
-- the migration rather than quietly delete accounts.
do $$
declare
    v_orphaned int;
begin
    select count(*) into v_orphaned from app_user where clinic_id is null;
    if v_orphaned > 0 then
        raise exception 'V14: % app_user row(s) have no membership and would be deleted -- refusing to run against a non-greenfield database', v_orphaned;
    end if;
end $$;

delete from app_user where clinic_id is null;

alter table app_user alter column clinic_id set not null;

-- The "oldest membership wins" backfill above only sets app_user.clinic_id;
-- it doesn't touch any OTHER membership row a user might already have in a
-- different clinic. The guard trigger created below only checks future
-- inserts/updates, so pre-existing multi-clinic memberships would otherwise
-- sit silently in violation of the very invariant this migration introduces.
-- Fail loudly now instead of surfacing as a confusing trigger error later on
-- an unrelated update to one of these rows.
do $$
declare
    v_mismatched int;
begin
    select count(*) into v_mismatched
    from membership m
    join app_user u on u.id = m.user_id
    where m.clinic_id is distinct from u.clinic_id;
    if v_mismatched > 0 then
        raise exception 'V14: % membership row(s) disagree with their user''s backfilled clinic_id -- this user has memberships in more than one clinic, which the new one-account-per-clinic model cannot represent', v_mismatched;
    end if;
end $$;

-- 3. Per-clinic username uniqueness replaces the global one.
alter table app_user drop constraint app_user_username_key;
create unique index app_user_clinic_username_key on app_user (clinic_id, username);

-- 4. Email follows the same scope: uniqueness is now per clinic, so the same
--    address can be reused across two different clinics (only a duplicate
--    within one clinic is rejected).
drop index idx_app_user_email_when_not_null;
create unique index idx_app_user_email_when_not_null on app_user (clinic_id, email) where email is not null;

-- 5. app_rw needs the new column (V9 revoked blanket select and grants
--    column-by-column).
grant select (clinic_id) on app_user to app_rw;

-- 6. Guard trigger: a membership's clinic_id must match its user's
--    app_user.clinic_id. Same style as V10.
create or replace function membership_clinic_matches_user()
returns trigger as $$
declare
    v_user_clinic uuid;
begin
    select clinic_id into v_user_clinic
    from public.app_user
    where id = new.user_id;

    if v_user_clinic is null then
        raise exception 'app_user % not found', new.user_id;
    end if;

    if v_user_clinic is distinct from new.clinic_id then
        raise exception 'cross-tenant membership: membership % clinic_id % does not match its user''s clinic_id %',
            new.id, new.clinic_id, v_user_clinic;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_membership_clinic_matches_user
    before insert or update on membership
    for each row
    execute function membership_clinic_matches_user();

-- 7. Pre-auth lookup keyed by (clinic_slug, username), replacing the V11
--    username-keyed one. Same SECURITY DEFINER hardening.
create function app_user_credentials_lookup_by_clinic_username(p_slug text, p_username citext)
returns table (id uuid, clinic_id uuid, password_hash text, status text)
language sql
security definer
stable
set search_path = pg_catalog, public, pg_temp
as $$
    select u.id, u.clinic_id, u.password_hash, u.status
    from public.app_user u
    join public.clinic c on c.id = u.clinic_id
    where c.slug = p_slug and u.username = p_username;
$$;

revoke all on function app_user_credentials_lookup_by_clinic_username(text, citext) from public;
grant execute on function app_user_credentials_lookup_by_clinic_username(text, citext) to app_rw;

-- 8. Drop the superseded pre-auth functions.
drop function app_user_credentials_lookup_by_username(citext);
drop function app_user_credentials_lookup(citext);

-- 9. Signup inserts the owner user with its new clinic's id and returns the
--    slug so the UI can show the owner their clinic code. Drop first: the
--    return row type (now includes slug) differs from the V13 OUT params.
drop function signup_clinic_with_owner(text, text, text, citext, citext, text);
create function signup_clinic_with_owner(
    p_clinic_name text,
    p_slug text,
    p_full_name text,
    p_username citext,
    p_email citext,
    p_password_hash text
)
returns table (user_id uuid, clinic_id uuid, membership_id uuid, slug text)
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

    insert into public.app_user (email, password_hash, full_name, username, status, clinic_id)
    values (p_email, p_password_hash, p_full_name, p_username, 'active', v_clinic_id)
    returning id into v_user_id;

    insert into public.membership (clinic_id, user_id, role_id, status)
    select v_clinic_id, v_user_id, id, 'active'
    from public.role
    where code = 'owner'
    returning id into v_membership_id;

    if v_membership_id is null then
        raise exception 'owner role missing from public.role';
    end if;

    return query select v_user_id, v_clinic_id, v_membership_id, p_slug;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;
