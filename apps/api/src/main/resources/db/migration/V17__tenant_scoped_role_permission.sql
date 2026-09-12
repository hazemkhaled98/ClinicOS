-- Tenant-scoped role_permission (slice 2d).
--
-- role_permission was a platform-level table (no clinic_id), meaning every
-- clinic shared the same permission matrix. This migration adds clinic_id,
-- enables RLS, backfills existing clinics, and seeds the signup function so
-- new clinics get the default permission set.

-- 1. Add clinic_id column.
alter table role_permission add column clinic_id uuid;

-- 2. Backfill: for each existing clinic, copy the current global rows.
--    Uses a cross join to create one (clinic, role, permission) tuple per clinic.
insert into role_permission (role_id, permission_id, clinic_id)
select rp.role_id, rp.permission_id, c.id
from role_permission rp
cross join clinic c
where rp.clinic_id is null
on conflict do nothing;

-- 2b. Remove the original global rows (no clinic_id) now that per-clinic copies exist.
delete from role_permission where clinic_id is null;

-- 3. Now enforce NOT NULL and add FK.
alter table role_permission alter column clinic_id set not null;
alter table role_permission
    add constraint fk_role_permission_clinic
    foreign key (clinic_id) references clinic (id) on delete cascade;

-- 4. Drop the old PK (role_id, permission_id) and create a new one including clinic_id.
alter table role_permission drop constraint role_permission_pkey;
alter table role_permission
    add primary key (role_id, permission_id, clinic_id);

-- 5. Grant DML to app_rw (V9's blanket grant covers new columns, but explicit is safer).
grant select, insert, update, delete on role_permission to app_rw;

-- 6. Enable RLS with tenant_isolation on clinic_id.
alter table role_permission enable row level security;
create policy tenant_isolation on role_permission
    using (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid)
    with check (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid);

-- 7. Recreate signup_clinic_with_owner to also seed default role_permission rows.
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

    -- Seed default role_permission rows for this clinic (same defaults as V12).
    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r, public.permission p
    where r.code = 'owner';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array[
        'quick', 'ceo', 'tasksTab', 'acadVerify', 'acadEdit', 'emp',
        'tray', 'issue', 'procs', 'myprocs', 'manage', 'orders', 'receive', 'returns', 'suppliers',
        'dash', 'profit', 'analytics', 'waste', 'doctors', 'supAnalysis', 'received', 'itemAnalysis', 'approvals', 'ledger'
    ])
    where r.code = 'manager';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'tray', 'issue', 'procs', 'myprocs', 'manage'])
    where r.code = 'assistant';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'orders', 'receive', 'returns', 'suppliers', 'ledger'])
    where r.code = 'receptionist';

    return query select v_user_id, v_clinic_id, v_membership_id, p_slug;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;
