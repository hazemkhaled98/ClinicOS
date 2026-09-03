-- Row-Level Security. The app connects as app_rw (non-superuser, non-owner of
-- these tables) so RLS cannot be silently bypassed. Every request sets
-- `app.clinic_id` via `SET LOCAL` at the start of its transaction (see jOOQ
-- ExecuteListener in the application tier); every tenant table is scoped to it.
--
-- Platform tables (clinic, app_user, role, permission, role_permission) are
-- deliberately NOT RLS-scoped -- they are reached only through service code
-- that itself enforces cross-tenant rules (e.g. signup, membership creation).

create role app_rw noinherit login password 'CHANGE_ME_IN_DEPLOYMENT';

grant usage on schema public to app_rw;
grant select, insert, update, delete on all tables in schema public to app_rw;
alter default privileges in schema public grant select, insert, update, delete on tables to app_rw;

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
        execute format(
            'create policy tenant_isolation on %I using (clinic_id = current_setting(''app.clinic_id'', true)::uuid) with check (clinic_id = current_setting(''app.clinic_id'', true)::uuid)',
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
            'create policy tenant_isolation on %I using (exists (select 1 from %I p where p.id = %I.%I and p.clinic_id = current_setting(''app.clinic_id'', true)::uuid))',
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
                      and c.clinic_id = current_setting('app.clinic_id', true)::uuid));
