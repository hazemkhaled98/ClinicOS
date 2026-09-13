# ClinicOS dev seed: wipes ALL business data and reseeds one clinic with four
# users (owner / manager / assistant / receptionist). Idempotent.
#
# Destructive: deletes every business row. Reference data (role, permission)
# and Flyway history are kept; per-clinic role_permission + config defaults are
# rebuilt by signup_clinic_with_owner (same path a fresh sign-up uses).
#
# Login (username-based): clinic slug "test-clinic", usernames owner / manager /
# assistant / receptionist. Owner password: 12345678. Everyone else: 123.

Set-Location $PSScriptRoot

docker info > $null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Docker engine down; starting Docker Desktop" -ForegroundColor Cyan
    Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        docker info > $null 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { throw "Docker engine did not start within 120s" }
}

Write-Host "==> Ensuring postgres is up" -ForegroundColor Cyan
docker compose up -d postgres 2>$null

$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    if ((docker inspect --format "{{.State.Health.Status}}" clinicos-postgres 2>$null) -eq "healthy") {
        $healthy = $true
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $healthy) { throw "postgres did not become healthy within 60s" }

Write-Host "==> Wiping data + seeding users" -ForegroundColor Cyan

$sql = @'
\set ON_ERROR_STOP on

-- Wipe every business table. Keeps: role, permission, flyway_schema_history.
select 'truncate table ' || string_agg(format('%I.%I', n.nspname, c.relname), ', ') || ' restart identity cascade'
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind = 'r'
  and c.relname not in ('flyway_schema_history', 'role', 'permission')
\gexec

-- Owner clinic via the sanctioned signup path: clinic + owner membership +
-- role_permission defaults + clinic/gamification config defaults for one shot.
select user_id, clinic_id, membership_id, slug
from signup_clinic_with_owner(
    'Test Clinic',
    'test-clinic',
    'owner',
    'owner',
    'owner@clinicos.local',
    '$argon2id$v=19$m=16384,t=2,p=1$RHV8wbTDMBsviqO2hhduXQ$B+2MJPdsFrqDSAeEJLLxBP9mt9F8HoKMp/ZP1/6P1wU'
) \gset

-- manager / assistant / receptionist
insert into public.app_user (username, full_name, password_hash, email, status, clinic_id)
values
    ('manager',      'manager',      '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o', 'manager@clinicos.local',     'active', :'clinic_id'::uuid),
    ('assistant',    'assistant',    '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o', 'assistant@clinicos.local',   'active', :'clinic_id'::uuid),
    ('receptionist', 'receptionist', '$argon2id$v=19$m=16384,t=2,p=1$bx2rD762EEI7wUyrwLLZQg$2KMjr6vRkVFXnsYr5IuU1njo2BSDl2RhAqjTLw5jT+o', 'receptionist@clinicos.local','active', :'clinic_id'::uuid);

insert into public.membership (clinic_id, user_id, role_id, status)
select :'clinic_id'::uuid, u.id, r.id, 'active'
from public.app_user u
join public.role r on r.code = u.username
where u.clinic_id = :'clinic_id'::uuid and r.code <> 'owner';

select 'clinic_id=' || :'clinic_id' as seeded_clinic;
'@

$sql | docker exec -i clinicos-postgres psql -U postgres -d clinicos
if ($LASTEXITCODE -ne 0) { throw "seed failed" }

Write-Host "==> Done. Clinic slug: test-clinic" -ForegroundColor Green
Write-Host "    owner       / 12345678" -ForegroundColor Green
Write-Host "    manager     / 123" -ForegroundColor Green
Write-Host "    assistant   / 123" -ForegroundColor Green
Write-Host "    receptionist/ 123" -ForegroundColor Green