-- Employee leave requests (backlog item #2, Phase D).
--
-- The requested-and-decided layer above the V20 work calendar: an employee asks
-- for a date range off, and an approver accepts or refuses it. clinic_holiday
-- stays the calendar of days the clinic is shut or one employee is off by
-- administrative decision; leave_request is the workflow that produces a day off
-- for one employee as the result of a decision.
--
-- Approved leave is NOT copied into clinic_holiday. isWorkday / workdaysBetween
-- read this table directly (a date inside an approved range is a day off for
-- that employee), so a request is the single record to reconcile against an
-- evaluation instead of N day rows with no link back to the decision that
-- created them.

create extension if not exists btree_gist;

create type leave_request_status as enum ('pending', 'approved', 'rejected');

create table leave_request (
    id            uuid primary key default gen_random_uuid(),
    clinic_id     uuid not null references clinic (id) on delete cascade,
    employee_id   uuid not null references employee (id) on delete cascade,
    start_date    date not null,
    end_date      date not null,
    reason        text not null,
    status        leave_request_status not null default 'pending',
    decided_by    uuid references membership (id),
    decided_at    timestamptz,
    decision_note text,
    created_at    timestamptz not null default now(),
    check (end_date >= start_date),
    -- A decision is all-or-nothing: it always names who and when, and a
    -- rejection always explains itself. A pending row carries neither.
    check (
        (status = 'pending' and decided_by is null and decided_at is null and decision_note is null)
        or (status = 'approved' and decided_by is not null and decided_at is not null)
        or (status = 'rejected' and decided_by is not null and decided_at is not null
            and decision_note is not null and length(btrim(decision_note)) > 0)
    )
);

create index idx_leave_request_clinic_employee on leave_request (clinic_id, employee_id, start_date);
-- The pending queue filters by clinic without employee_id; this partial index
-- keeps that lookup limited to pending requests.
create index idx_leave_request_pending on leave_request (clinic_id, start_date) where status = 'pending';

-- One active request per employee per day. Rejected rows are excluded so a
-- refusal can be re-asked as a new request; btree_gist supplies the uuid
-- equality operator class the daterange exclusion needs. This is the
-- concurrency-safe home for the rule -- the service checks the same condition
-- for a friendlier Arabic message, but two concurrent submits would both pass
-- that check and only this constraint stops the second.
alter table leave_request
    add constraint leave_request_no_active_overlap
    exclude using gist (
        clinic_id with =,
        employee_id with =,
        daterange(start_date, end_date, '[]') with &&
    ) where (status in ('pending', 'approved'));

-- Row-Level Security (same pattern as V9__rls_policies.sql direct_tables).
alter table leave_request enable row level security;
create policy tenant_isolation on leave_request
    using (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid)
    with check (clinic_id = nullif(current_setting('app.clinic_id', true), '')::uuid);

-- Same-clinic FK guards: an employee or a deciding membership from another
-- clinic must never be reachable, even by a caller that is not RLS-scoped.
create trigger trg_leave_request_employee_same_clinic
    before insert or update on leave_request
    for each row
    execute function assert_same_clinic('employee_id', 'employee');

create trigger trg_leave_request_decided_by_same_clinic
    before insert or update on leave_request
    for each row
    execute function assert_same_clinic('decided_by', 'membership');
