-- Academy (onboarding) and prep checklists.
-- Replaces config.academy, EMPLOYEE.onboard, acadimg:, prep:list, prepRun:.

create type academy_audience as enum ('core', 'assistant', 'receptionist');
create type submission_status as enum ('pending', 'verified', 'rejected');
create type checklist_status as enum ('draft', 'approved');

create table academy_unit (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    applies_to      academy_audience not null,
    title           text not null,
    display_order   integer not null default 0,
    requires_photo  boolean not null default false,
    archived_at     timestamptz
);

create index idx_academy_unit_clinic on academy_unit (clinic_id, applies_to);

create table academy_question (
    id              uuid primary key default gen_random_uuid(),
    unit_id         uuid not null references academy_unit (id) on delete cascade,
    prompt          text not null,
    options         jsonb not null,
    correct_index   integer not null check (correct_index >= 0)
);

create index idx_academy_question_unit on academy_question (unit_id);

create table academy_step_submission (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    employee_id     uuid not null references employee (id) on delete cascade,
    unit_id         uuid not null references academy_unit (id) on delete cascade,
    photo_id        uuid, -- FK to attachment added in V8
    submitted_at    timestamptz not null default now(),
    verified_by     uuid references membership (id),
    verified_at     timestamptz,
    status          submission_status not null default 'pending'
);

create index idx_academy_submission_clinic on academy_step_submission (clinic_id, employee_id);

create table academy_exam_attempt (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    employee_id     uuid not null references employee (id) on delete cascade,
    score           integer not null check (score between 0 and 100),
    passed          boolean not null,
    attempted_at    timestamptz not null default now()
);

create index idx_academy_exam_clinic on academy_exam_attempt (clinic_id, employee_id, attempted_at desc);

create table prep_checklist (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    name            text not null,
    status          checklist_status not null default 'draft',
    approved_by     uuid references membership (id),
    approved_at     timestamptz,
    archived_at     timestamptz
);

create index idx_prep_checklist_clinic on prep_checklist (clinic_id);

create table prep_section (
    id              uuid primary key default gen_random_uuid(),
    checklist_id    uuid not null references prep_checklist (id) on delete cascade,
    title           text not null,
    display_order   integer not null default 0
);

create index idx_prep_section_checklist on prep_section (checklist_id);

create table prep_item (
    id              uuid primary key default gen_random_uuid(),
    section_id      uuid not null references prep_section (id) on delete cascade,
    name            text not null,
    display_order   integer not null default 0
);

create index idx_prep_item_section on prep_item (section_id);

create table prep_run (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    checklist_id    uuid not null references prep_checklist (id) on delete cascade,
    employee_id     uuid not null references employee (id) on delete cascade,
    run_date        date not null,
    unique (clinic_id, checklist_id, employee_id, run_date)
);

create index idx_prep_run_clinic on prep_run (clinic_id, run_date);

create table prep_run_item (
    prep_run_id     uuid not null references prep_run (id) on delete cascade,
    prep_item_id    uuid not null references prep_item (id) on delete cascade,
    checked_at      timestamptz,
    primary key (prep_run_id, prep_item_id)
);
