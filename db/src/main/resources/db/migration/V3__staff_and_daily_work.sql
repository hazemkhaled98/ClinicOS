-- Staff and daily work. Replaces config.employees, daily:, selfcheck:, assign:.

create type staff_role as enum ('assistant', 'receptionist');
create type task_dimension as enum ('fanni', 'solooki', 'ibda3');
create type task_frequency as enum ('daily', 'weekly', 'monthly', 'custom');
create type interval_unit as enum ('day', 'week', 'month');
create type assignment_proposer as enum ('manager', 'employee');
create type assignment_status as enum ('pending', 'approved', 'rejected');

create table employee (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    name            text not null,
    staff_role      staff_role not null,
    base_pay        numeric(10,2) not null check (base_pay >= 0),
    max_incentive   numeric(10,2) not null check (max_incentive >= 0),
    shift_start     time,
    shift_end       time,
    custom_shift    boolean not null default false,
    hired_at        date not null default current_date,
    archived_at     timestamptz
);

create index idx_employee_clinic on employee (clinic_id);

-- Now that employee exists, wire the deferred FK from membership.
alter table membership
    add constraint fk_membership_employee foreign key (employee_id) references employee (id) on delete set null;

create table task_definition (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    staff_role      staff_role not null,
    name            text not null,
    dimension       task_dimension not null,
    frequency       task_frequency not null,
    every_n         integer check (every_n >= 1),
    interval_unit   interval_unit,
    requires_photo  boolean not null default false,
    display_order   integer not null default 0,
    archived_at     timestamptz,
    created_at      timestamptz not null default now(),
    check ((frequency = 'custom') = (every_n is not null and interval_unit is not null))
);

create index idx_task_definition_clinic on task_definition (clinic_id, staff_role);

create table daily_record (
    id          uuid primary key default gen_random_uuid(),
    clinic_id   uuid not null references clinic (id) on delete cascade,
    employee_id uuid not null references employee (id) on delete cascade,
    work_date   date not null,
    fanni       smallint check (fanni between 1 and 5),
    solooki     smallint check (solooki between 1 and 5),
    ibda3       smallint check (ibda3 between 1 and 5),
    rated_by    uuid references membership (id),
    rated_at    timestamptz,
    unique (employee_id, work_date)
);

create index idx_daily_record_clinic on daily_record (clinic_id, work_date);

create table daily_task_completion (
    daily_record_id     uuid not null references daily_record (id) on delete cascade,
    task_definition_id  uuid not null references task_definition (id) on delete cascade,
    done                boolean not null default false,
    completed_at        timestamptz,
    photo_id            uuid, -- FK to attachment added in V8
    primary key (daily_record_id, task_definition_id)
);

create table self_check (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    employee_id     uuid not null references employee (id) on delete cascade,
    work_date       date not null,
    checked_in_at   timestamptz not null,
    checked_out_at  timestamptz,
    unique (employee_id, work_date)
);

create index idx_self_check_clinic on self_check (clinic_id, work_date);

create table task_assignment (
    id                      uuid primary key default gen_random_uuid(),
    clinic_id               uuid not null references clinic (id) on delete cascade,
    employee_id             uuid not null references employee (id) on delete cascade,
    name                    text not null,
    proposed_by             assignment_proposer not null,
    assigned_at             timestamptz not null default now(),
    due_date                date,
    status                  assignment_status not null default 'pending',
    approved_by             uuid references membership (id),
    approved_at             timestamptz,
    done_at                 timestamptz,
    proof_photo_id          uuid,        -- FK to attachment added in V8
    verification_photo_id   uuid         -- FK to attachment added in V8
);

create index idx_task_assignment_clinic on task_assignment (clinic_id, employee_id);
