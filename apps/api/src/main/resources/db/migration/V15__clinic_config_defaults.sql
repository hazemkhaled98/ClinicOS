-- Seed clinic-level configuration defaults for every clinic.
--
-- clinic_settings / evaluation_weight / incentive_tier existed since V2 but
-- nothing populated them: a new clinic only got a clinic_settings row with
-- column defaults (grace 10 min, no volume target) and zero weights and tiers.
-- The evaluation module needs all six weights and the four incentive tiers
-- (BR-G07) to run, so both paths that create a clinic must seed them:
--
--   1. signup_clinic_with_owner seeds the rows for a brand-new clinic, and
--   2. this migration backfills clinics that already exist.
--
-- Values are the legacy defaults (index_original.html:900, :927): shift
-- 09:00-17:00, grace 15 min, volume target 20 000 EGP, working days 26/month,
-- weights completion 18 / fanni 18 / solooki 12 / ibda3 22 / volume 18 /
-- attendance 12, tiers >=90 ممتاز 100% / >=75 جيد جداً 75% / >=60 جيد 50% /
-- else يحتاج تطوير 0%.

-- 1. Recreate the signup function so a new clinic starts fully configured.
create or replace function signup_clinic_with_owner(
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

    return query select v_user_id, v_clinic_id, v_membership_id, p_slug;
end;
$$;

revoke all on function signup_clinic_with_owner(text, text, text, citext, citext, text) from public;
grant execute on function signup_clinic_with_owner(text, text, text, citext, citext, text) to app_rw;

-- 2. Backfill clinics created before this migration. All three blocks are
--    idempotent (on conflict do nothing) so re-running the SQL by hand is safe.
insert into public.clinic_settings (
    clinic_id, default_shift_start, default_shift_end, late_grace_minutes,
    working_days_per_month, volume_target, academy_pass_score
)
select c.id, '09:00', '17:00', 15, 26, 20000, 70
from public.clinic c
on conflict (clinic_id) do nothing;

insert into public.evaluation_weight (clinic_id, category, weight)
select c.id, v.category, v.weight
from public.clinic c
cross join (values
    ('completion'::public.eval_category, 18),
    ('fanni'::public.eval_category, 18),
    ('solooki'::public.eval_category, 12),
    ('ibda3'::public.eval_category, 22),
    ('volume'::public.eval_category, 18),
    ('attendance'::public.eval_category, 12)
) as v(category, weight)
on conflict (clinic_id, category) do nothing;

insert into public.incentive_tier (clinic_id, name, min_score, incentive_pct)
select c.id, v.name, v.min_score, v.incentive_pct
from public.clinic c
cross join (values
    ('ممتاز', 90, 100),
    ('جيد جداً', 75, 75),
    ('جيد', 60, 50),
    ('يحتاج تطوير', 0, 0)
) as v(name, min_score, incentive_pct)
on conflict (clinic_id, name) do nothing;