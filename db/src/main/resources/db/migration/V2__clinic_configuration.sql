-- Clinic-level configuration. Replaces the legacy `config` blob (shift defaults,
-- evaluation weights, incentive tiers).

create type eval_category as enum ('completion', 'fanni', 'solooki', 'ibda3', 'attendance', 'volume');

create table clinic_settings (
    clinic_id               uuid primary key references clinic (id) on delete cascade,
    default_shift_start     time not null default '09:00',
    default_shift_end       time not null default '17:00',
    late_grace_minutes      integer not null default 10 check (late_grace_minutes >= 0),
    working_days_per_month  integer not null default 26 check (working_days_per_month > 0),
    volume_target           numeric(12,2) check (volume_target >= 0),
    academy_pass_score      integer not null default 70 check (academy_pass_score between 0 and 100)
);

create table evaluation_weight (
    clinic_id   uuid not null references clinic (id) on delete cascade,
    category    eval_category not null,
    weight      numeric(5,2) not null check (weight >= 0 and weight <= 100),
    primary key (clinic_id, category)
);

create table incentive_tier (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    name            text not null,
    min_score       numeric(5,2) not null check (min_score between 0 and 100),
    incentive_pct   numeric(5,2) not null check (incentive_pct between 0 and 100),
    unique (clinic_id, name)
);

create index idx_incentive_tier_clinic on incentive_tier (clinic_id);
