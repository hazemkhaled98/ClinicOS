-- Work calendar: weekday mask + holidays (UC-003, Slice 3a).

-- 1. Add working_weekdays to clinic_settings (ISO-8601 day-of-week: 1=Mon..7=Sun;
--    default Sat-Thu = {6,7,1,2,3,4}).
alter table clinic_settings
    add column working_weekdays smallint[] not null default '{6,7,1,2,3,4}';

alter table clinic_settings
    add constraint clinic_settings_working_weekdays_check
    check (
        array_length(working_weekdays, 1) > 0
        and working_weekdays <@ array[1,2,3,4,5,6,7]::smallint[]
    );

-- 2. Holidays (clinic-wide when employee_id is null, per-employee otherwise).
create table clinic_holiday (
    id          uuid primary key default gen_random_uuid(),
    clinic_id   uuid not null references clinic(id) on delete cascade,
    holiday_date date not null,
    name        text not null,
    employee_id uuid null references employee(id) on delete cascade,
    unique (clinic_id, holiday_date, employee_id)
);

create index idx_clinic_holiday_clinic_date on clinic_holiday (clinic_id, holiday_date);

-- 3. Row-Level Security (same pattern as V9__rls_policies.sql direct_tables).
alter table clinic_holiday enable row level security;
create policy tenant_isolation on clinic_holiday
    using (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid)
    with check (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid);

-- 4. Same-clinic FK guard (same pattern as V10__triggers.sql assert_same_clinic specs).
create trigger trg_clinic_holiday_employee_id_same_clinic
    before insert or update on clinic_holiday
    for each row
    execute function assert_same_clinic('employee_id', 'employee');
