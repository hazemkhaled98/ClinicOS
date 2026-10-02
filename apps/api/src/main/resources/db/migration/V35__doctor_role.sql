insert into role (code, name) values ('doctor', 'Doctor')
on conflict (code) do nothing;

insert into permission (code, name) values ('academy', 'الوصول إلى الأكاديمية')
on conflict (code) do nothing;

insert into role_permission (role_id, permission_id, clinic_id)
select r.id, p.id, c.id
from role r
cross join permission p
cross join clinic c
where p.code = 'academy'
  and r.code in ('owner', 'manager', 'assistant', 'receptionist', 'doctor')
on conflict do nothing;

insert into role_permission (role_id, permission_id, clinic_id)
select r.id, p.id, c.id
from role r
join permission p on p.code in ('emp', 'myprocs')
cross join clinic c
where r.code = 'doctor'
on conflict do nothing;

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

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r, public.permission p
    where r.code = 'owner';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array[
        'quick', 'ceo', 'tasksTab', 'acadVerify', 'acadEdit', 'academy', 'emp',
        'tray', 'issue', 'procs', 'myprocs', 'manage', 'orders', 'receive', 'returns', 'suppliers',
        'dash', 'profit', 'analytics', 'waste', 'doctors', 'supAnalysis', 'received', 'itemAnalysis', 'approvals', 'ledger'
    ])
    where r.code = 'manager';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'tray', 'issue', 'procs', 'myprocs', 'manage', 'academy'])
    where r.code = 'assistant';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'orders', 'receive', 'returns', 'suppliers', 'ledger', 'academy'])
    where r.code = 'receptionist';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'myprocs', 'academy'])
    where r.code = 'doctor';

    return query select v_user_id, v_clinic_id, v_membership_id, p_slug;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;
