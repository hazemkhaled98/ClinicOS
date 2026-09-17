-- Task completion review (UC-004, Slice 4a): manager approves or rejects an
-- employee's daily task completion. Only approved completions count toward
-- the evaluation (UC-004 BR-002, BR-G12); daily_task_completion gains the
-- review columns. Tenancy is inherited from daily_record via the existing V9
-- child-policy, so no RLS change is needed here.

create type task_review_status as enum ('pending', 'approved', 'rejected');

alter table daily_task_completion
    add column review_status task_review_status not null default 'pending',
    add column review_reason text,
    add column reviewed_by uuid references membership (id),
    add column reviewed_at timestamptz;

-- V9's blanket table-level grant (applied before these columns existed)
-- already covers them, but this consumer is also used by callers who reason
-- about column grants explicitly (see the V11 username trap); make the intent
-- self-documenting and future-proof against a later V9-style revoke.
grant select, insert, update (review_status, review_reason, reviewed_by, reviewed_at)
    on daily_task_completion to app_rw;