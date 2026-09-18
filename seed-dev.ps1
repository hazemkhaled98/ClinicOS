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
  and c.relname not in ('flyway_schema_history', 'role', 'permission', 'prep_template', 'prep_template_section', 'prep_template_item')
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

with seeded_employees as (
    select u.id as user_id, u.clinic_id, u.username as name
    from public.app_user u
    where u.clinic_id = :'clinic_id'::uuid
      and u.username in ('manager', 'assistant', 'receptionist')
), inserted_employees as (
    insert into public.employee (clinic_id, name, base_pay, max_incentive)
    select clinic_id, name, 0, 0
    from seeded_employees
    returning id, clinic_id, name
)
update public.membership m
set employee_id = e.id
from inserted_employees e
join public.app_user u on u.clinic_id = e.clinic_id and u.username = e.name
where m.clinic_id = e.clinic_id
  and m.user_id = u.id;

do $catalog$
begin
    if (select count(*) from public.prep_template) <> 3
       or (select count(*) from public.prep_template_item) <> 33
       or not exists (
           select 1 from public.prep_template_section
           where title = 'الأوبتريشن (الحشو)'
       ) then
        truncate table public.prep_template restart identity cascade;
        insert into public.prep_template (code, name, display_order)
        values
            ('examination', 'إكزامينيشن (كشف وتشخيص)', 1),
            ('anesthesia', 'أنستيزيا (تخدير موضعي)', 2),
            ('endo', 'إندو (سحب عصب)', 3);

        insert into public.prep_template_section (template_id, title, display_order)
        select t.id, x.title, x.ord
        from public.prep_template t
        cross join lateral unnest(case t.code
            when 'examination' then array['تجهيز عام', 'دياجنوزيس']
            when 'anesthesia' then array['الأنستيزيا']
            else array['تجهيز عام', 'أنستيزيا', 'أكسيس وتنظيف الكانالز', 'الأوبتريشن (الحشو)', 'إنهاء وتعقيم']
        end) with ordinality x(title, ord);

        insert into public.prep_template_item (section_id, name, display_order)
        select s.id, x.name, x.ord
        from public.prep_template t
        join public.prep_template_section s on s.template_id = t.id
        cross join lateral unnest(case t.code || ':' || s.display_order
            when 'examination:1' then array['ميرور (مرآة فحص)', 'إكسبلورر (مسبار)', 'تويزر (ملقط)', 'جلوفز ومناديل', 'كوب ومحلول مضمضة']
            when 'examination:2' then array['خافض لسان/تشيك ريتراكتور', 'جوز (شاش معقّم)', 'إنترا-أورال كاميرا (لو متاح)', 'كارت تسجيل الحالة']
            when 'anesthesia:1' then array['توبيكال جل (تخدير سطحي)', 'أسبيريتنج سرنجة', 'كاربيول أنستيتيك (كبسولة بنج)', 'نيدل مناسبة (شورت/لونج)', 'قطن وجوز']
            when 'endo:1' then array['ميرور وإكسبلورر وتويزر', 'سكشن (شفّاط)', 'رَبر دام + فريم']
            when 'endo:2' then array['توبيكال جل', 'سرنجة وكاربيول ونيدل']
            when 'endo:3' then array['تيربين هاند بيس + بَرز', 'كي-فايلز (هاند فايلز) بمقاسات', 'روتاري فايلز (لو متاح)', 'هيبوكلورايت (NaOCl)', 'إريجيشن سرنجة', 'EDTA', 'بيبر بوينتس', 'إيبكس لوكيتور']
            when 'endo:4' then array['جوتا بركا بوينتس', 'سيلر (معجون حشو)', 'سبريدر/بلجر', 'تمبوراري فيلينج']
            else array['إكس-راي للتأكد', 'تعقيم الأدوات']
        end) with ordinality x(name, ord);
    end if;
end $catalog$;

create temp table _seed_scope (clinic_id uuid);
insert into _seed_scope values (:'clinic_id'::uuid);

do $check$
declare
    seed_clinic uuid;
    catalog_templates integer;
    catalog_items integer;
    missing_staff integer;
    owner_employee_count integer;
    cross_clinic_links integer;
begin
    select clinic_id into seed_clinic from _seed_scope;
    select count(*) into catalog_templates from public.prep_template;
    select count(*) into catalog_items from public.prep_template_item;
    select count(*) into missing_staff
    from public.membership m
    join public.app_user u on u.id = m.user_id
    where m.clinic_id = seed_clinic
      and u.username in ('manager', 'assistant', 'receptionist')
      and m.employee_id is null;
    select count(*) into owner_employee_count
    from public.membership m
    join public.app_user u on u.id = m.user_id
    where m.clinic_id = seed_clinic
      and u.username = 'owner'
      and m.employee_id is not null;
    select count(*) into cross_clinic_links
    from public.membership m
    join public.employee e on e.id = m.employee_id
    where m.clinic_id = seed_clinic
      and e.clinic_id <> m.clinic_id;
    if catalog_templates <> 3 or catalog_items <> 33 then
        raise exception 'unexpected prep catalog: templates %, items %', catalog_templates, catalog_items;
    end if;
    if missing_staff <> 0 then
        raise exception 'staff memberships missing employee links: %', missing_staff;
    end if;
    if owner_employee_count <> 0 then
        raise exception 'owner must not have employee link';
    end if;
    if cross_clinic_links <> 0 then
        raise exception 'cross-clinic employee links: %', cross_clinic_links;
    end if;
end $check$;

drop table _seed_scope;

select 'clinic_id=' || :'clinic_id' as seeded_clinic;
'@

$sql | docker exec -i clinicos-postgres psql -U postgres -d clinicos
if ($LASTEXITCODE -ne 0) { throw "seed failed" }

Write-Host "==> Done. Clinic slug: test-clinic" -ForegroundColor Green
Write-Host "    owner       / 12345678" -ForegroundColor Green
Write-Host "    manager     / 123" -ForegroundColor Green
Write-Host "    assistant   / 123" -ForegroundColor Green
Write-Host "    receptionist/ 123" -ForegroundColor Green
