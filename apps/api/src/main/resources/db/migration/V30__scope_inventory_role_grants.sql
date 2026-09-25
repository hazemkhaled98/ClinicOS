-- Scope inventory role grants to BR-001.
--
-- BR-001 says an assistant sees only tray stock, issuing, and their own
-- procedures, and a receptionist sees only ordering, receiving, returns, and
-- suppliers. The V12/V17 defaults over-granted two codes:
--   * receptionist had `ledger`, the clinic-wide stock-movement log.
--   * assistant had `procs`, the all-procedures list rather than `myprocs`.
--
-- `manage` stays with the assistant: UC-008 step 5 requires a non-manager to be
-- able to file an item edit or delete, and the catalogue/queue is how that form
-- is reached.

-- 1. Remove the over-grants from every existing clinic's role_permission.
delete from role_permission rp
using role r, permission p
where rp.role_id = r.id
  and rp.permission_id = p.id
  and (r.code = 'receptionist' and p.code = 'ledger'
       or r.code = 'assistant' and p.code = 'procs');

-- 2. New clinics must not receive them at signup either, so re-create the
--    function with the trimmed arrays. The rest of the body must stay
--    identical to V18 (clinic/evaluation/gamification seeding); only the
--    assistant and receptionist arrays differ.
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
        'quick', 'ceo', 'tasksTab', 'acadVerify', 'acadEdit', 'emp',
        'tray', 'issue', 'procs', 'myprocs', 'manage', 'orders', 'receive', 'returns', 'suppliers',
        'dash', 'profit', 'analytics', 'waste', 'doctors', 'supAnalysis', 'received', 'itemAnalysis', 'approvals', 'ledger'
    ])
    where r.code = 'manager';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'tray', 'issue', 'myprocs', 'manage'])
    where r.code = 'assistant';

    insert into public.role_permission (role_id, permission_id, clinic_id)
    select r.id, p.id, v_clinic_id
    from public.role r
    join public.permission p on p.code = any(array['emp', 'orders', 'receive', 'returns', 'suppliers'])
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

    -- Seed gamification defaults. The function's RETURNS TABLE clause makes
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
