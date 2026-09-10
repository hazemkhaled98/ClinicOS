-- Gamification tables (slice 2e).
--
-- Three clinic-scoped tables: visibility settings, weekly goals, badge
-- thresholds. All follow the standard clinic_id RLS pattern (V9 loop adds
-- them to direct_tables via V18).

-- 1. Settings: one row per clinic.
create table gamification_settings (
    clinic_id           uuid primary key references clinic (id) on delete cascade,
    show_level_ring     boolean not null default true,
    show_streaks        boolean not null default true,
    show_badges         boolean not null default true,
    show_weekly_goals   boolean not null default true,
    show_leaderboard    boolean not null default false,
    show_reward         boolean not null default true
);

-- 2. Weekly goals: up to 3 slots per clinic.
create table weekly_goal (
    clinic_id   uuid not null references clinic (id) on delete cascade,
    slot        integer not null check (slot between 1 and 3),
    title       text not null default '',
    target      integer not null default 0,
    primary key (clinic_id, slot)
);

-- 3. Badge thresholds: named thresholds per clinic.
create table badge_threshold (
    clinic_id   uuid not null references clinic (id) on delete cascade,
    name        text not null,
    threshold   integer not null default 0,
    primary key (clinic_id, name)
);

-- 4. Enable RLS on all three tables.
do $$
declare
    t text;
begin
    foreach t in array array['gamification_settings', 'weekly_goal', 'badge_threshold'] loop
        execute format('alter table %I enable row level security', t);
        execute format(
            $f$create policy tenant_isolation on %I using (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid) with check (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid)$f$,
            t
        );
    end loop;
end $$;

-- 5. Grant DML to app_rw.
grant select, insert, update, delete on gamification_settings, weekly_goal, badge_threshold to app_rw;

-- 6. Recreate signup_clinic_with_owner to also seed gamification defaults.
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

    -- Seed default role_permission rows for this clinic.
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

    -- Seed clinic configuration defaults (same values as V15) so a new
    -- clinic starts fully configured for the evaluation and duty modules.
    insert into public.clinic_settings (
        clinic_id, default_shift_start, default_shift_end, late_grace_minutes,
        working_days_per_month, volume_target, academy_pass_score
    )
    values (v_clinic_id, '09:00', '17:00', 15, 26, 20000, 70);

    insert into public.evaluation_weight (clinic_id, category, weight)
    values
        (v_clinic_id, 'completion'::public.eval_category, 18),
        (v_clinic_id, 'fanni'::public.eval_category, 18),
        (v_clinic_id, 'solooki'::public.eval_category, 12),
        (v_clinic_id, 'ibda3'::public.eval_category, 22),
        (v_clinic_id, 'volume'::public.eval_category, 18),
        (v_clinic_id, 'attendance'::public.eval_category, 12);

    insert into public.incentive_tier (clinic_id, name, min_score, incentive_pct)
    values
        (v_clinic_id, 'ممتاز', 90, 100),
        (v_clinic_id, 'جيد جداً', 75, 75),
        (v_clinic_id, 'جيد', 60, 50),
        (v_clinic_id, 'يحتاج تطوير', 0, 0);

    -- Seed gamification defaults. The functions RETURNS TABLE clause makes
    -- `clinic_id` a PL/pgSQL variable, so ON CONFLICT must target the
    -- constraint by name instead of the bare column.
    insert into public.gamification_settings (clinic_id)
    values (v_clinic_id)
    on conflict on constraint gamification_settings_pkey do nothing;

    insert into public.weekly_goal (clinic_id, slot, title, target)
    values (v_clinic_id, 1, '', 0), (v_clinic_id, 2, '', 0), (v_clinic_id, 3, '', 0)
    on conflict on constraint weekly_goal_pkey do nothing;

    insert into public.badge_threshold (clinic_id, name, threshold)
    values (v_clinic_id, 'نجم الأسبوع', 0), (v_clinic_id, 'الأكثر إنجازاً', 0),
           (v_clinic_id, 'مبدع', 0), (v_clinic_id, 'ملتزم', 0), (v_clinic_id, 'متميز', 0)
    on conflict on constraint badge_threshold_pkey do nothing;

    return query select v_user_id, v_clinic_id, v_membership_id, p_slug;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;
