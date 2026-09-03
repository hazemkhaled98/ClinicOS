-- Tenancy & identity: clinic root, platform users, memberships, role/permission model.
-- Replaces legacy config.users, the owner boolean, and invRole overrides.

create extension if not exists pgcrypto;
create extension if not exists citext;

create type clinic_status as enum ('trial', 'active', 'suspended', 'cancelled');
create type membership_status as enum ('invited', 'active', 'suspended');

create table clinic (
    id          uuid primary key default gen_random_uuid(),
    name        text not null,
    slug        text not null unique,
    timezone    text not null default 'Africa/Cairo',
    locale      text not null default 'ar-EG',
    currency    text not null default 'EGP',
    status      clinic_status not null default 'trial',
    created_at  timestamptz not null default now()
);

create table app_user (
    id              uuid primary key default gen_random_uuid(),
    email           citext not null unique,
    password_hash   text not null,
    full_name       text not null,
    status          text not null default 'active' check (status in ('active', 'suspended')),
    last_login_at   timestamptz,
    created_at      timestamptz not null default now()
);

-- Platform-defined roles. No clinic_id: roles are shared across all tenants.
create table role (
    id      uuid primary key default gen_random_uuid(),
    code    text not null unique,   -- owner, manager, assistant, receptionist
    name    text not null
);

create table permission (
    id      uuid primary key default gen_random_uuid(),
    code    text not null unique,   -- e.g. employees.manage, inventory.approve
    name    text not null
);

create table role_permission (
    role_id         uuid not null references role (id) on delete cascade,
    permission_id   uuid not null references permission (id) on delete cascade,
    primary key (role_id, permission_id)
);

-- Join of user <-> clinic. Login is app_user; authorization is membership.
create table membership (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    user_id         uuid not null references app_user (id) on delete cascade,
    role_id         uuid not null references role (id),
    employee_id     uuid, -- FK to employee added in V3 (employee table doesn't exist yet)
    status          membership_status not null default 'active',
    created_at      timestamptz not null default now(),
    unique (clinic_id, user_id)
);

create index idx_membership_clinic on membership (clinic_id);
create index idx_membership_user on membership (user_id);

-- Per-member grant/revoke override on top of the role's default permission set.
create table membership_permission (
    membership_id   uuid not null references membership (id) on delete cascade,
    permission_id   uuid not null references permission (id) on delete cascade,
    granted         boolean not null,
    primary key (membership_id, permission_id)
);

create index idx_membership_permission_clinic on membership_permission (membership_id);

-- Seed the fixed platform roles referenced throughout the business rules (BR-G03, BR-G04).
insert into role (code, name) values
    ('owner', 'Owner'),
    ('manager', 'Manager'),
    ('assistant', 'Assistant'),
    ('receptionist', 'Receptionist');
