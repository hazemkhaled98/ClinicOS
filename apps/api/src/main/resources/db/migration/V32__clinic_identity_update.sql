create function update_clinic_identity(
    p_clinic_id uuid,
    p_actor_membership_id uuid,
    p_name text,
    p_slug text
)
returns void
language plpgsql
security definer
volatile
set search_path = pg_catalog, public, pg_temp
as $$
begin
    if p_clinic_id is distinct from nullif(current_setting('app.clinic_id', true), '')::uuid then
        raise exception 'tenant mismatch for clinic %', p_clinic_id;
    end if;

    if not exists (
        select 1
        from public.membership m
        join public.role r on r.id = m.role_id
        where m.id = p_actor_membership_id
          and m.clinic_id = p_clinic_id
          and m.status = 'active'
          and r.code = 'owner'
    ) then
        raise exception 'only the clinic owner may change clinic identity';
    end if;

    update public.clinic
    set name = p_name, slug = p_slug
    where id = p_clinic_id;
end;
$$;

revoke all on function update_clinic_identity(uuid, uuid, text, text) from public;
grant execute on function update_clinic_identity(uuid, uuid, text, text) to app_rw;
