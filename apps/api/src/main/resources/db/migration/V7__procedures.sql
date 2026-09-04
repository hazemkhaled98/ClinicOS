-- Procedures and per-case costing. patient_ref is deliberately an opaque code,
-- never a patient name/phone, to keep this out of health-record regulatory scope.

create table procedure (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    name            text not null,
    price           numeric(10,2) not null check (price >= 0),
    labor_cost      numeric(10,2) not null default 0 check (labor_cost >= 0),
    doctor_fee      numeric(10,2) not null default 0 check (doctor_fee >= 0),
    archived_at     timestamptz
);

create index idx_procedure_clinic on procedure (clinic_id);

create table procedure_bom (
    procedure_id    uuid not null references procedure (id) on delete cascade,
    item_id         uuid not null references inventory_item (id),
    qty             numeric(12,2) not null check (qty > 0),
    primary key (procedure_id, item_id)
);

create table procedure_case (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    procedure_id    uuid not null references procedure (id),
    employee_id     uuid not null references employee (id),
    doctor_name     text,
    patient_ref     text,   -- opaque clinic-side reference, never a patient name
    performed_at    timestamptz not null default now()
);

create index idx_procedure_case_clinic on procedure_case (clinic_id, performed_at);

create table procedure_case_item (
    id                  uuid primary key default gen_random_uuid(),
    procedure_case_id   uuid not null references procedure_case (id) on delete cascade,
    item_id             uuid not null references inventory_item (id),
    qty                 numeric(12,2) not null check (qty > 0),
    unit_cost_at_time   numeric(10,2) not null check (unit_cost_at_time >= 0)
);

create index idx_procedure_case_item_case on procedure_case_item (procedure_case_id);
