-- Task targets: retire the staff_role enum on task_definition. A task may
-- target one role (manager included), all roles = both null, or one employee.
-- role.code is the single source of role truth; nullability is the target.

-- 1. Add nullable target columns and backfill before dropping the enum.
alter table task_definition add column role_code text;
alter table task_definition add column employee_id uuid;

update task_definition set role_code = staff_role::text;

-- Drop the old discriminator index first: dropping the staff_role column
-- below would auto-drop it, and we need a clean swap to the new shape.
drop index idx_task_definition_clinic;

alter table task_definition drop column staff_role;
drop type staff_role;

-- 2. Constraints: role_code is a real role, employee_id is a same-clinic
-- employee, and a task targets either a role or an employee, never both.
alter table task_definition
    add constraint fk_task_definition_role foreign key (role_code) references role (code);
alter table task_definition
    add constraint fk_task_definition_employee foreign key (employee_id) references employee (id) on delete cascade;
alter table task_definition
    add constraint task_definition_target_check check (role_code is null or employee_id is null);

-- 3. Clinic-scoped index on the new discriminator.
create index idx_task_definition_clinic on task_definition (clinic_id, role_code);

-- 4. Cross-tenant FK guard on employee_id (same pattern as V20; short-circuits
-- on NULL so role/all-role rows are unaffected).
create trigger trg_task_definition_employee_same_clinic
    before insert or update on task_definition
    for each row
    execute function assert_same_clinic('employee_id', 'employee');