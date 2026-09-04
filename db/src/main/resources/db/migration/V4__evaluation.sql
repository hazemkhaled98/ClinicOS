-- Evaluation: manual overrides, frozen monthly snapshots, per-category components,
-- recorded operating volume. Replaces override:, evalSnap:, invoice:.

create table performance_override (
    employee_id     uuid not null references employee (id) on delete cascade,
    period_month    date not null,   -- always day 1 of the month
    category        eval_category not null,
    floor_value     numeric(5,2) not null check (floor_value between 0 and 100),
    set_by          uuid references membership (id),
    set_at          timestamptz not null default now(),
    primary key (employee_id, period_month, category),
    check (extract(day from period_month) = 1)
);

create table evaluation_snapshot (
    id                  uuid primary key default gen_random_uuid(),
    clinic_id           uuid not null references clinic (id) on delete cascade,
    employee_id         uuid not null references employee (id) on delete cascade,
    period_month        date not null,
    final_score         numeric(5,2) not null check (final_score between 0 and 100),
    incentive_amount    numeric(10,2) not null default 0 check (incentive_amount >= 0),
    frozen_at           timestamptz not null default now(),
    frozen_by           uuid references membership (id),
    unlocked_at         timestamptz,
    unlocked_by         uuid references membership (id),
    unique (employee_id, period_month),
    check (extract(day from period_month) = 1)
);

create index idx_evaluation_snapshot_clinic on evaluation_snapshot (clinic_id, period_month);

create table evaluation_component (
    snapshot_id     uuid not null references evaluation_snapshot (id) on delete cascade,
    category        eval_category not null,
    raw_score       numeric(5,2) check (raw_score between 0 and 100),
    weight          numeric(5,2) not null check (weight >= 0),
    included        boolean not null default true,
    primary key (snapshot_id, category)
);

create table operations_volume (
    clinic_id       uuid not null references clinic (id) on delete cascade,
    period_month    date not null,
    amount          numeric(12,2) not null check (amount >= 0),
    recorded_by     uuid references membership (id),
    recorded_at     timestamptz not null default now(),
    primary key (clinic_id, period_month),
    check (extract(day from period_month) = 1)
);
