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
        'supplier', 'inventory_item', 'stock_location', 'stock_movement',
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

-- Child tables scoped through a parent's clinic_id.
alter table membership_permission enable row level security;
create policy tenant_isolation on membership_permission
    using (exists (select 1 from membership m where m.id = membership_permission.membership_id
                     and m.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table daily_task_completion enable row level security;
create policy tenant_isolation on daily_task_completion
    using (exists (select 1 from daily_record d where d.id = daily_task_completion.daily_record_id
                     and d.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table performance_override enable row level security;
create policy tenant_isolation on performance_override
    using (exists (select 1 from employee e where e.id = performance_override.employee_id
                     and e.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table evaluation_component enable row level security;
create policy tenant_isolation on evaluation_component
    using (exists (select 1 from evaluation_snapshot s where s.id = evaluation_component.snapshot_id
                     and s.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table academy_question enable row level security;
create policy tenant_isolation on academy_question
    using (exists (select 1 from academy_unit u where u.id = academy_question.unit_id
                     and u.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table prep_section enable row level security;
create policy tenant_isolation on prep_section
    using (exists (select 1 from prep_checklist c where c.id = prep_section.checklist_id
                     and c.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table prep_item enable row level security;
create policy tenant_isolation on prep_item
    using (exists (select 1 from prep_section s join prep_checklist c on c.id = s.checklist_id
                    where s.id = prep_item.section_id
                      and c.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table prep_run_item enable row level security;
create policy tenant_isolation on prep_run_item
    using (exists (select 1 from prep_run r where r.id = prep_run_item.prep_run_id
                     and r.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table stock_alert_threshold enable row level security;
create policy tenant_isolation on stock_alert_threshold
    using (exists (select 1 from inventory_item i where i.id = stock_alert_threshold.item_id
                     and i.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table purchase_order_line enable row level security;
create policy tenant_isolation on purchase_order_line
    using (exists (select 1 from purchase_order o where o.id = purchase_order_line.order_id
                     and o.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table supplier_return_line enable row level security;
create policy tenant_isolation on supplier_return_line
    using (exists (select 1 from supplier_return r where r.id = supplier_return_line.supplier_return_id
                     and r.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table procedure_bom enable row level security;
create policy tenant_isolation on procedure_bom
    using (exists (select 1 from procedure p where p.id = procedure_bom.procedure_id
                     and p.clinic_id = current_setting('app.clinic_id', true)::uuid));

alter table procedure_case_item enable row level security;
create policy tenant_isolation on procedure_case_item
    using (exists (select 1 from procedure_case c where c.id = procedure_case_item.procedure_case_id
                     and c.clinic_id = current_setting('app.clinic_id', true)::uuid));
