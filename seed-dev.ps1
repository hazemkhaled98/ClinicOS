# ClinicOS dev seed: wipes ALL non-reference data and reseeds one clinic with
# four users (owner / manager / assistant / receptionist). Idempotent.
#
# Destructive: truncates all public tables except reference/catalog data
# (role, permission, prep_template, prep_template_section, prep_template_item).
#
# Per-clinic role_permission + config defaults are rebuilt by
# signup_clinic_with_owner (same path a fresh sign-up uses).
#
# Login (username-based):
#   Clinic slug: test-clinic
#
#   owner        / 12345678
#   manager      / 123
#   assistant    / 123
#   receptionist / 123

Set-Location $PSScriptRoot

$exitCode = 0

try {
    # -------------------------------------------------------------------------
    # Docker
    # -------------------------------------------------------------------------

    docker info > $null 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host "==> Docker engine down; starting Docker Desktop" -ForegroundColor Cyan

        Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"

        $ready = $false

        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 2

            docker info > $null 2>&1

            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
        }

        if (-not $ready) {
            throw "Docker engine did not start within 120s"
        }
    }

    # -------------------------------------------------------------------------
    # PostgreSQL
    # -------------------------------------------------------------------------

    Write-Host "==> Ensuring postgres is up" -ForegroundColor Cyan

    docker compose up -d postgres 2>$null

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start postgres"
    }

    $healthy = $false

    for ($i = 0; $i -lt 30; $i++) {
        $status = docker inspect `
            --format "{{.State.Health.Status}}" `
            clinicos-postgres 2>$null

        if ($status -eq "healthy") {
            $healthy = $true
            break
        }

        Start-Sleep -Seconds 2
    }

    if (-not $healthy) {
        throw "postgres did not become healthy within 60s"
    }

    # -------------------------------------------------------------------------
    # Seed
    # -------------------------------------------------------------------------

    Write-Host "==> Wiping data + seeding users" -ForegroundColor Cyan

    $sql = @'
\set ON_ERROR_STOP on

-- Wipe all non-reference tables.
-- Keeps role, permission, and prep catalog data.

select
    'truncate table ' ||
    string_agg(
        format('%I.%I', n.nspname, c.relname),
        ', '
    ) ||
    ' restart identity cascade'
from pg_class c
join pg_namespace n
    on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind = 'r'
  and c.relname not in (
      'role',
      'permission',
      'prep_template',
      'prep_template_section',
      'prep_template_item',
      'academy_template_unit',
      'academy_template_question',
      'flyway_schema_history'
  )
\gexec


-- Owner clinic via the sanctioned signup path:
-- clinic + owner membership + role_permission defaults +
-- clinic/gamification config defaults.

select
    user_id,
    clinic_id,
    membership_id,
    slug
from signup_clinic_with_owner(
    'Test Clinic',
    'test-clinic',
    'owner',
    'owner',
    'owner@clinicos.local',
    '$argon2id$v=19$m=16384,t=2,p=1$RHV8wbTDMBsviqO2hhduXQ$B+2MJPdsFrqDSAeEJLLxBP9mt9F8HoKMp/ZP1/6P1wU'
)
\gset


-- Create manager / assistant / receptionist users.

insert into public.app_user (
    username,
    full_name,
    password_hash,
    email,
    status,
    clinic_id
)
values
    (
        'manager',
        'manager',
        '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o',
        'manager@clinicos.local',
        'active',
        :'clinic_id'::uuid
    ),
    (
        'assistant',
        'assistant',
        '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o',
        'assistant@clinicos.local',
        'active',
        :'clinic_id'::uuid
    ),
    (
        'receptionist',
        'receptionist',
        '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o',
        'receptionist@clinicos.local',
        'active',
        :'clinic_id'::uuid
    );


-- Create memberships for staff.

insert into public.membership (
    clinic_id,
    user_id,
    role_id,
    status
)
select
    :'clinic_id'::uuid,
    u.id,
    r.id,
    'active'
from public.app_user u
join public.role r
    on r.code = u.username
where u.clinic_id = :'clinic_id'::uuid
  and r.code <> 'owner';


-- Create employee records for staff.
-- Owner intentionally does not get an employee record.

with seeded_employees as (
    select
        u.id as user_id,
        u.clinic_id,
        u.username as name
    from public.app_user u
    where u.clinic_id = :'clinic_id'::uuid
      and u.username in (
          'manager',
          'assistant',
          'receptionist'
      )
),
inserted_employees as (
    insert into public.employee (
        clinic_id,
        name,
        base_pay,
        max_incentive
    )
    select
        clinic_id,
        name,
        0,
        0
    from seeded_employees
    returning
        id,
        clinic_id,
        name
)
update public.membership m
set employee_id = e.id
from inserted_employees e
join public.app_user u
    on u.clinic_id = e.clinic_id
   and u.username = e.name
where m.clinic_id = e.clinic_id
  and m.user_id = u.id;


-- ---------------------------------------------------------------------------
-- Validation
-- ---------------------------------------------------------------------------

create temp table _seed_scope (
    clinic_id uuid
);

insert into _seed_scope
values (:'clinic_id'::uuid);


do $check$
declare
    seed_clinic uuid;
    catalog_templates integer;
    catalog_items integer;
    missing_staff integer;
    owner_employee_count integer;
    cross_clinic_links integer;
begin

    select clinic_id
    into seed_clinic
    from _seed_scope;


    select count(*)
    into catalog_templates
    from public.prep_template;


    select count(*)
    into catalog_items
    from public.prep_template_item;


    select count(*)
    into missing_staff
    from public.membership m
    join public.app_user u
        on u.id = m.user_id
    where m.clinic_id = seed_clinic
      and u.username in (
          'manager',
          'assistant',
          'receptionist'
      )
      and m.employee_id is null;


    select count(*)
    into owner_employee_count
    from public.membership m
    join public.app_user u
        on u.id = m.user_id
    where m.clinic_id = seed_clinic
      and u.username = 'owner'
      and m.employee_id is not null;


    select count(*)
    into cross_clinic_links
    from public.membership m
    join public.employee e
        on e.id = m.employee_id
    where m.clinic_id = seed_clinic
      and e.clinic_id <> m.clinic_id;


    if catalog_templates <> 3 or catalog_items <> 33 then
        raise exception
            'unexpected prep catalog: templates %, items %',
            catalog_templates,
            catalog_items;
    end if;


    if missing_staff <> 0 then
        raise exception
            'staff memberships missing employee links: %',
            missing_staff;
    end if;


    if owner_employee_count <> 0 then
        raise exception
            'owner must not have employee link';
    end if;


    if cross_clinic_links <> 0 then
        raise exception
            'cross-clinic employee links: %',
            cross_clinic_links;
    end if;

end
$check$;


drop table _seed_scope;


-- Print seeded clinic ID.

select
    'clinic_id=' || :'clinic_id'
as seeded_clinic;

'@

    # -------------------------------------------------------------------------
    # Execute seed
    # -------------------------------------------------------------------------

    $sql | docker exec -i clinicos-postgres `
        psql `
        -U postgres `
        -d clinicos

    if ($LASTEXITCODE -ne 0) {
        throw "seed failed"
    }

    # -------------------------------------------------------------------------
    # Success
    # -------------------------------------------------------------------------

    Write-Host ""
    Write-Host "==> Done."
    Write-Host "    Clinic slug: test-clinic" -ForegroundColor Green
    Write-Host ""
    Write-Host "    owner        / 12345678" -ForegroundColor Green
    Write-Host "    manager      / 123" -ForegroundColor Green
    Write-Host "    assistant    / 123" -ForegroundColor Green
    Write-Host "    receptionist / 123" -ForegroundColor Green
}
catch {
    $exitCode = 1

    Write-Host ""
    Write-Host "========================================" -ForegroundColor Red
    Write-Host " SEED FAILED" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host $_.Exception.Message -ForegroundColor Red
}
finally {
    Write-Host ""
    Write-Host "Press any key to close" -ForegroundColor DarkGray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}

exit $exitCode