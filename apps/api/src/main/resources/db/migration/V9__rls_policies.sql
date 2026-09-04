-- Row-Level Security. The app connects as app_rw (non-superuser, non-owner of
-- these tables) so RLS cannot be silently bypassed. Every request sets
-- `app.clinic_id` via `SET LOCAL` at the start of its transaction (see jOOQ
-- ExecuteListener in the application tier); every tenant table is scoped to it.
--
-- Platform tables (clinic, app_user, role, permission, role_permission) are
-- deliberately NOT RLS-scoped -- they are reached only through service code
-- that itself enforces cross-tenant rules (e.g. signup, membership creation).
-- app_rw only gets read access to them; writes go through a privileged path,
-- not the per-request tenant connection.
--
-- RESOLVED in V11: membership is RLS-scoped by clinic_id like any tenant
-- table, but looking up "which clinics does this user belong to" at login
-- happens before app.clinic_id is known. V11's app_user_memberships_lookup
-- (a SECURITY DEFINER function, same pattern as app_user_credentials_lookup
-- below) is the sanctioned pre-tenant path, reached through TenantContext's
-- auth-mode escape (nil UUID bound instead of a real clinic_id).
--
-- clinic itself is not RLS-scoped (it has no clinic_id -- it IS the tenant),
-- so app_rw can still SELECT every clinic's name/slug/settings row, not just
-- its own. Lower severity than the password_hash issue fixed below (business
-- metadata, not credentials); the clinic picker reads it through the same
-- auth-mode path as membership above, so this was never actually a blocker.

-- Role creation must be idempotent: roles are cluster-global, so a bare
-- `create role` fails (and leaves Flyway's migration history stuck) the
-- second time this runs against a cluster that already has app_rw -- a second
-- database, a CI reset, a restore. Password is set out of band at deployment,
-- never committed here.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'app_rw') then
        create role app_rw noinherit login;
    end if;
end $$;

grant usage on schema public to app_rw;
grant select, insert, update, delete on all tables in schema public to app_rw;
revoke insert, update, delete on clinic, app_user, role, permission, role_permission from app_rw;
alter default privileges in schema public grant select, insert, update, delete on tables to app_rw;

-- supplier_return isn't itself append-only, but supplier_return_line (its
-- child) is meant to be: trg_supplier_return_line_immutable (V10) only
-- forbids a DIRECT delete on that table, and deliberately lets a delete
-- CASCADED IN from its own parent through (so a clinic/inventory_item with
-- ledger history can still be removed). supplier_return is an ordinary
-- business row with no such exemption reason, so leaving DELETE granted on
-- it would let app_rw erase a whole line of "immutable" history just by
-- deleting the return that owns it. There's no legitimate flow that deletes
-- a return outright anyway -- rejecting one is a status change, not removal.
revoke delete on supplier_return from app_rw;

-- app_user.password_hash must never be reachable by a blanket table SELECT --
-- a single injection or logic bug on the tenant connection would otherwise
-- dump every user's credential hash across every tenant. Column-level grant
-- excludes it; the login path gets it only through this SECURITY DEFINER
-- function (owned by the migration role, which does have full table access,
-- so it can return the column app_rw itself cannot see directly).
--
-- search_path MUST list pg_temp last, explicitly, and every relation MUST be
-- schema-qualified: app_rw keeps its default CREATE TEMP TABLE right, and
-- without pg_temp pinned to the end, Postgres resolves an unqualified name
-- against the session's temp schema BEFORE `public` -- app_rw could shadow
-- app_user with `create temp table app_user (...)` and this SECURITY
-- DEFINER function would read the attacker's row instead, handing back
-- whatever password_hash the attacker put there. That defeats the whole
-- point of the lockdown: the same "logic bug on the tenant connection" this
-- function exists to contain is exactly what lets an attacker create that
-- temp table in the first place.
revoke select on app_user from app_rw;
grant select (id, email, full_name, status, last_login_at, created_at) on app_user to app_rw;

create function app_user_credentials_lookup(p_email citext)
returns table (id uuid, password_hash text, status text)
language sql
security definer
stable
set search_path = pg_catalog, public, pg_temp
as $$
    select id, password_hash, status from public.app_user where email = p_email;
$$;

revoke all on function app_user_credentials_lookup(citext) from public;
grant execute on function app_user_credentials_lookup(citext) to app_rw;

-- Flyway's own bookkeeping table isn't a business table but sits in the same
-- schema, so the blanket grant above swept it in too -- the tenant
-- connection has no business rewriting migration history. Guarded by
-- existence: Flyway creates this table before running any migration, so it's
-- always present in a real deployment, but a manual/partial replay of just
-- this file (e.g. schema verification tooling) may not have it.
do $$
begin
    if exists (select 1 from pg_tables where schemaname = 'public' and tablename = 'flyway_schema_history') then
        revoke all on flyway_schema_history from app_rw;
    end if;
end $$;

-- Tables carrying clinic_id directly: uniform policy.
do $$
declare
    t text;
    direct_tables text[] := array[
        'membership',
        'clinic_settings', 'evaluation_weight', 'incentive_tier',
        'employee', 'task_definition', 'daily_record', 'self_check', 'task_assignment',
        'evaluation_snapshot', 'operations_volume',
        'academy_unit', 'academy_step_submission', 'academy_exam_attempt', 'prep_checklist', 'prep_run',
        'supplier', 'inventory_item', 'stock_movement',
        'purchase_order', 'supplier_return', 'inventory_change_request',
        'procedure', 'procedure_case',
        'attachment', 'activity_log', 'notification'
    ];
begin
    foreach t in array direct_tables loop
        execute format('alter table %I enable row level security', t);
        -- nullif(...,'') guards against the GUC having been SET LOCAL earlier
        -- in the session and left as '' once that transaction ended --
        -- current_setting(x, true) only returns NULL the first time, never
        -- again, so a bare ''::uuid cast would raise on every later request
        -- that hasn't set app.clinic_id yet in its own transaction. Dollar-
        -- quoting the format string avoids nested single-quote escaping.
        execute format(
            $f$create policy tenant_isolation on %I using (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid) with check (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid)$f$,
            t
        );
    end loop;
end $$;

-- Child tables scoped through a parent's clinic_id: same loop technique as
-- above, parameterized by (child table, its FK column, parent table).
do $$
declare
    child_table  text;
    fk_column    text;
    parent_table text;
    child_tables text[][] := array[
        ['membership_permission', 'membership_id', 'membership'],
        ['daily_task_completion', 'daily_record_id', 'daily_record'],
        ['performance_override', 'employee_id', 'employee'],
        ['evaluation_component', 'snapshot_id', 'evaluation_snapshot'],
        ['academy_question', 'unit_id', 'academy_unit'],
        ['prep_section', 'checklist_id', 'prep_checklist'],
        ['prep_run_item', 'prep_run_id', 'prep_run'],
        ['purchase_order_line', 'order_id', 'purchase_order'],
        ['supplier_return_line', 'supplier_return_id', 'supplier_return'],
        ['procedure_bom', 'procedure_id', 'procedure'],
        ['procedure_case_item', 'procedure_case_id', 'procedure_case']
    ];
begin
    for i in 1 .. array_upper(child_tables, 1) loop
        child_table  := child_tables[i][1];
        fk_column    := child_tables[i][2];
        parent_table := child_tables[i][3];
        execute format('alter table %I enable row level security', child_table);
        execute format(
            $f$create policy tenant_isolation on %I using (exists (select 1 from %I p where p.id = %I.%I and p.clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid))$f$,
            child_table, parent_table, child_table, fk_column
        );
    end loop;
end $$;

-- prep_item is scoped two hops away (via prep_section to prep_checklist), so
-- it doesn't fit the single-parent loop above.
alter table prep_item enable row level security;
create policy tenant_isolation on prep_item
    using (exists (select 1 from prep_section s join prep_checklist c on c.id = s.checklist_id
                    where s.id = prep_item.section_id
                      and c.clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid));
