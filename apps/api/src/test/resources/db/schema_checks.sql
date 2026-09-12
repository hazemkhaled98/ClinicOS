-- Run as app_rw, after seed_two_clinics.sql, with:
--   psql -v ON_ERROR_STOP=1 -U app_rw -d <db> -f schema_checks.sql
--
-- Every check below is a self-asserting DO block: a rule that's supposed to
-- raise an error is attempted inside a nested BEGIN/EXCEPTION, so the
-- expected error is swallowed there and never reaches psql -- if it does NOT
-- raise, that's the regression, and THIS script raises 'REGRESSION: ...'
-- instead, which is an unhandled top-level error psql (and CI) will see and
-- fail on. A rule that's supposed to succeed just asserts the resulting
-- value; a mismatch raises the same way. This means the whole file can (and
-- should) run under ON_ERROR_STOP=1: nothing here is expected to error at
-- the top level, only REGRESSION conditions are.
--
-- Each DO block is its own implicit transaction (this isn't wrapped in an
-- explicit BEGIN/COMMIT), so `set_config(..., true)` (SET LOCAL semantics)
-- only needs to be called once per block and is set again at the top of the
-- next one -- matching how the app sets app.clinic_id once per request.

\set clinic_a '11111111-1111-1111-1111-111111111111'
\set clinic_b '22222222-2222-2222-2222-222222222222'

-- ===========================================================================
-- 1. RLS isolation: reads are scoped to the session's clinic.
-- ===========================================================================

do $$
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    if (select count(*) from employee where name = 'Sara Ahmed') <> 1 then
        raise exception 'REGRESSION: clinic A should see exactly 1 ''Sara Ahmed'' (its own), not clinic B''s';
    end if;
    if (select count(*) from task_definition where name = 'Sterilize tray') <> 1 then
        raise exception 'REGRESSION: clinic A should see exactly 1 ''Sterilize tray'' task, not clinic B''s';
    end if;
    -- prep_item's two-hop policy (the one that doesn't fit the templated
    -- single-parent loop) must still resolve clinic A's own item.
    if (select count(*) from prep_item where name = 'Files') <> 1 then
        raise exception 'REGRESSION: prep_item two-hop RLS policy did not surface clinic A''s own row';
    end if;
end $$;

do $$
begin
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);
    -- Clinic B has its own stock_movement and purchase_order_line rows;
    -- clinic A's equivalents (seeded first, larger quantities) must be
    -- invisible -- proves the direct-clinic_id loop AND the child-table
    -- loop (purchase_order_line's policy goes through purchase_order).
    if (select coalesce(sum(qty_delta), 0) from stock_movement where item_id = 'eeeeeeee-0000-0000-0000-000000000002') <> 50 then
        raise exception 'REGRESSION: clinic B should see its own 50-unit receipt on stock_movement';
    end if;
    if exists (select 1 from stock_movement where item_id = 'eeeeeeee-0000-0000-0000-000000000001') then
        raise exception 'REGRESSION: clinic B can see clinic A''s stock_movement rows';
    end if;
    if exists (select 1 from supplier_return_line where supplier_return_id = '30000000-0000-0000-0000-000000000001') then
        raise exception 'REGRESSION: clinic B can see clinic A''s supplier_return_line (child-table RLS via supplier_return)';
    end if;
    if exists (select 1 from prep_item where name = 'Files') then
        raise exception 'REGRESSION: clinic B can see clinic A''s prep_item (two-hop RLS)';
    end if;
end $$;

-- ===========================================================================
-- 2. Cross-tenant writes are rejected by the RLS WITH CHECK clause.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    begin
        insert into employee (clinic_id, name, base_pay, max_incentive)
        values ('22222222-2222-2222-2222-222222222222', 'Cross Tenant', 100, 10);
        unexpected_success := true;
    exception when others then
        null; -- expected: RLS WITH CHECK rejects the row
    end;
    if unexpected_success then
        raise exception 'REGRESSION: inserting a row with another clinic''s clinic_id under session clinic A succeeded';
    end if;
end $$;

-- ===========================================================================
-- 3. Frozen evaluation_snapshot lifecycle (BR-G05), including the re-freeze
--    behavior the ponytail-simplified comment originally got wrong: the
--    unlock is spent by the very next edit, not left open indefinitely.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    -- 3a. Still frozen: UPDATE must raise.
    begin
        update evaluation_snapshot set final_score = 99 where id = '40000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: updating a frozen evaluation_snapshot succeeded';
    end if;

    -- 3b. Still frozen: DELETE must raise.
    unexpected_success := false;
    begin
        delete from evaluation_snapshot where id = '40000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: deleting a frozen evaluation_snapshot succeeded';
    end if;

    -- 3c. Still frozen: editing a child evaluation_component must raise.
    unexpected_success := false;
    begin
        update evaluation_component set raw_score = 50
        where snapshot_id = '40000000-0000-0000-0000-000000000001' and category = 'fanni';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: editing evaluation_component of a frozen snapshot succeeded';
    end if;
end $$;

do $$
declare
    v_final_score numeric;
    v_unlocked_at timestamptz;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 3d. Unlock: must succeed (this is the one statement always allowed
    -- while frozen -- it's what makes the row editable).
    update evaluation_snapshot set unlocked_at = now() where id = '40000000-0000-0000-0000-000000000001';

    -- 3e. Now unlocked: editing the child component must succeed.
    update evaluation_component set raw_score = 80
    where snapshot_id = '40000000-0000-0000-0000-000000000001' and category = 'fanni';

    -- 3f. Now unlocked: editing the snapshot itself must succeed, and this
    -- edit spends the unlock (re-nulls unlocked_at as a side effect).
    update evaluation_snapshot set final_score = 90 where id = '40000000-0000-0000-0000-000000000001';

    select final_score, unlocked_at into v_final_score, v_unlocked_at
    from evaluation_snapshot where id = '40000000-0000-0000-0000-000000000001';

    if v_final_score is distinct from 90.00 then
        raise exception 'REGRESSION: expected final_score=90.00 after the unlocked edit, got %', v_final_score;
    end if;
    if v_unlocked_at is not null then
        raise exception 'REGRESSION: expected unlocked_at to be re-nulled (unlock spent) after the edit, still %', v_unlocked_at;
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 3g. Re-frozen: a second edit without another unlock must raise --
    -- this is the case the original trigger comment claimed but the
    -- original code didn't actually implement.
    begin
        update evaluation_snapshot set final_score = 10 where id = '40000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: editing evaluation_snapshot a second time without a fresh unlock succeeded (unlock was not spent)';
    end if;

    -- 3h. Re-frozen: the component is blocked again too.
    unexpected_success := false;
    begin
        update evaluation_component set raw_score = 10
        where snapshot_id = '40000000-0000-0000-0000-000000000001' and category = 'fanni';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: editing evaluation_component succeeded after the parent snapshot re-froze';
    end if;

    -- 3i. Combining the unlock with an edit in the SAME statement must raise
    -- rather than silently leaving the row unlocked-and-already-edited: a
    -- prior version of this trigger let exactly this through.
    unexpected_success := false;
    begin
        update evaluation_snapshot set unlocked_at = now(), final_score = 1
        where id = '40000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: unlocking and editing evaluation_snapshot in one statement succeeded';
    end if;
end $$;

-- ===========================================================================
-- 4. Return-quantity ceiling (BR-G27) and append-only immutability.
--    Line 20000000...0001: qty_received = 100, 10 already returned (approved).
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 4a. Remaining receivable is 90; returning 95 must raise. The setup
    -- insert (the return header itself) is deliberately OUTSIDE the guarded
    -- block below -- only the statement actually under test (the line insert
    -- that trips the ceiling trigger) is wrapped, so an unrelated setup
    -- failure can't masquerade as this check passing.
    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'pending');
    begin
        insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
        select id, '20000000-0000-0000-0000-000000000001', 95 from supplier_return
        where purchase_order_id = '10000000-0000-0000-0000-000000000001' and status = 'pending';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: returning 95 against a line with only 90 remaining receivable succeeded';
    end if;
end $$;

do $$
declare
    v_line_id uuid;
    v_returned numeric;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 4b. Boundary: returning exactly the remaining 90 must succeed (proves
    -- the ceiling is a strict ">", not an off-by-one "> vs >=" bug).
    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'approved')
    returning id into v_line_id;
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
    values (v_line_id, '20000000-0000-0000-0000-000000000001', 90);

    select coalesce(sum(rl.qty), 0) into v_returned
    from supplier_return_line rl join supplier_return r on r.id = rl.supplier_return_id
    where rl.purchase_order_line_id = '20000000-0000-0000-0000-000000000001' and r.status <> 'rejected';

    if v_returned <> 100 then
        raise exception 'REGRESSION: expected 100 total non-rejected returned qty (10 + 90) on the line, got %', v_returned;
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 4c. Immutability: UPDATE on an existing supplier_return_line must raise.
    begin
        update supplier_return_line set qty = 1 where purchase_order_line_id = '20000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: updating an existing supplier_return_line succeeded (should be append-only)';
    end if;

    -- 4d. Immutability: DELETE must raise too.
    unexpected_success := false;
    begin
        delete from supplier_return_line where purchase_order_line_id = '20000000-0000-0000-0000-000000000001';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: deleting an existing supplier_return_line succeeded (should be append-only)';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    -- 4e. Cross-tenant reference: as clinic A, point a return line at clinic
    -- B's purchase_order_line. The FK itself doesn't stop this (FK checks
    -- bypass RLS), so this specifically exercises the ceiling trigger's
    -- "not found" guard, which sees the line as absent under A's RLS. Setup
    -- (the return header) again sits outside the guarded block, same reason
    -- as 4a.
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'pending');
    begin
        insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
        select id, '20000000-0000-0000-0000-000000000002', 1 from supplier_return
        where purchase_order_id = '10000000-0000-0000-0000-000000000001' and status = 'pending'
        order by requested_at desc limit 1;
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: clinic A referencing clinic B''s purchase_order_line in a return succeeded';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 4f. stock_movement is also an append-only ledger: UPDATE must raise.
    begin
        update stock_movement set qty_delta = 0 where item_id = 'eeeeeeee-0000-0000-0000-000000000001' and reason = 'receipt';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: updating an existing stock_movement row succeeded (should be append-only)';
    end if;

    -- 4g. DELETE must raise too.
    unexpected_success := false;
    begin
        delete from stock_movement where item_id = 'eeeeeeee-0000-0000-0000-000000000001' and reason = 'receipt';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: deleting an existing stock_movement row succeeded (should be append-only)';
    end if;
end $$;

-- Stock movement sum still matches expected on-hand: 100 (receipt) - 20
-- (issue) - 10 (return) = 70. Untouched by the immutability checks above,
-- which only proved the attempted mutations were rejected.
do $$
declare
    v_sum numeric;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    select sum(qty_delta) into v_sum from stock_movement
    where item_id = 'eeeeeeee-0000-0000-0000-000000000001' and location = 'store';
    if v_sum is distinct from 70.00 then
        raise exception 'REGRESSION: expected on-hand 70.00 for the store location, got %', v_sum;
    end if;
end $$;

-- ===========================================================================
-- 5. Column-level constraints: rating range, per-day uniqueness, the
--    frequency/custom_shift biconditionals, and the exam-answer bound.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 5a. Rating out of 1..5 must raise.
    begin
        insert into daily_record (clinic_id, employee_id, work_date, fanni)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 6);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: daily_record.fanni = 6 was accepted';
    end if;

    -- 5b. Per-day uniqueness: a second daily_record for the same employee/day must raise.
    insert into daily_record (clinic_id, employee_id, work_date, fanni)
    values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 5);
    unexpected_success := false;
    begin
        insert into daily_record (clinic_id, employee_id, work_date, fanni)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 4);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: a second daily_record for the same employee/day was accepted';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 5c. task_definition: frequency='custom' without every_n/interval_unit must raise.
    begin
        insert into task_definition (clinic_id, staff_role, name, dimension, frequency)
        values ('11111111-1111-1111-1111-111111111111', 'assistant', 'Bad custom task', 'fanni', 'custom');
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: frequency=''custom'' with no every_n/interval_unit was accepted';
    end if;

    -- 5d. task_definition: a non-custom frequency WITH every_n/interval_unit
    -- set must also raise -- this is the gap the original biconditional
    -- check missed (it only required "not both null", not "both null").
    unexpected_success := false;
    begin
        insert into task_definition (clinic_id, staff_role, name, dimension, frequency, every_n, interval_unit)
        values ('11111111-1111-1111-1111-111111111111', 'assistant', 'Bad daily task', 'fanni', 'daily', 5, 'week');
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: frequency=''daily'' with every_n/interval_unit set was accepted';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 5e. employee.custom_shift = true with no shift_start/shift_end must raise.
    begin
        insert into employee (clinic_id, name, base_pay, max_incentive, custom_shift)
        values ('11111111-1111-1111-1111-111111111111', 'Bad Shift Employee', 100, 10, true);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: custom_shift=true with no shift_start/shift_end was accepted';
    end if;

    -- 5f. custom_shift = false with shift_start/shift_end set must also raise.
    unexpected_success := false;
    begin
        insert into employee (clinic_id, name, base_pay, max_incentive, custom_shift, shift_start, shift_end)
        values ('11111111-1111-1111-1111-111111111111', 'Bad Shift Employee 2', 100, 10, false, '08:00', '16:00');
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: custom_shift=false with shift_start/shift_end set was accepted';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 5g. academy_question.correct_index pointing past the end of options must raise.
    begin
        insert into academy_question (unit_id, prompt, options, correct_index)
        values ('80000000-0000-0000-0000-000000000001', 'Which comes first?', '["a", "b"]'::jsonb, 2);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: academy_question.correct_index=2 was accepted for a 2-element options array';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 5h. period_month must be day 1 of the month (evaluation_snapshot, shared
    -- check with performance_override and operations_volume).
    begin
        insert into operations_volume (clinic_id, period_month, amount)
        values ('11111111-1111-1111-1111-111111111111', '2026-08-15', 1000);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: operations_volume.period_month = 2026-08-15 (not day 1) was accepted';
    end if;

    -- 5i. The same period_month-is-day-1 check is copy-pasted onto
    -- evaluation_snapshot independently of operations_volume's copy (5h) --
    -- prove it wasn't pasted wrong there too.
    unexpected_success := false;
    begin
        insert into evaluation_snapshot (clinic_id, employee_id, period_month, final_score)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-15', 50);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: evaluation_snapshot.period_month = 2026-09-15 (not day 1) was accepted';
    end if;

    -- 5j. academy_question.correct_index must also reject negative values --
    -- distinct from 5g's upper-bound check (a negative index would pass the
    -- "< array_length" comparison and only the separate correct_index >= 0
    -- column check catches it).
    unexpected_success := false;
    begin
        insert into academy_question (unit_id, prompt, options, correct_index)
        values ('80000000-0000-0000-0000-000000000001', 'Negative index?', '["a", "b"]'::jsonb, -1);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: academy_question.correct_index=-1 was accepted';
    end if;

    -- 5k. academy_exam_attempt.score must stay within 0..100.
    unexpected_success := false;
    begin
        insert into academy_exam_attempt (clinic_id, employee_id, score, passed)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', 101, true);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: academy_exam_attempt.score=101 was accepted';
    end if;

    -- 5l. purchase_order_line.qty_received must not exceed qty_ordered.
    unexpected_success := false;
    begin
        insert into purchase_order_line (order_id, item_id, qty_ordered, unit_cost, qty_received)
        values ('10000000-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000001', 10, 5, 20);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: purchase_order_line.qty_received (20) exceeding qty_ordered (10) was accepted';
    end if;
end $$;

-- ===========================================================================
-- 6. Child-table RLS rejects cross-tenant WRITES (WITH CHECK), not just reads
--    (USING) -- section 1 only proved reads are isolated.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);

    -- 6a. prep_item's two-hop policy (doesn't fit the templated single-parent
    -- loop): clinic B inserting into clinic A's prep_section must raise.
    begin
        insert into prep_item (section_id, name) values ('60000000-0000-0000-0000-000000000001', 'Sneaked In');
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: clinic B inserting a prep_item under clinic A''s prep_section succeeded';
    end if;

    -- 6b. A representative templated child table (purchase_order_line,
    -- scoped via order_id -> purchase_order): clinic B pointing at clinic
    -- A's purchase_order must raise too. 11 tables share this exact
    -- generated policy shape (V9__rls_policies.sql child_tables loop); one
    -- representative is enough to catch a join-direction or column-name typo
    -- in the shared template.
    unexpected_success := false;
    begin
        insert into purchase_order_line (order_id, item_id, qty_ordered, unit_cost)
        values ('10000000-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000002', 1, 1);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: clinic B inserting a purchase_order_line under clinic A''s purchase_order succeeded';
    end if;
end $$;

-- ===========================================================================
-- 7. Rejected supplier_returns are excluded from the ceiling total, and
--    reinstating one (flipping it off 'rejected') re-triggers the check --
--    the ceiling trigger only runs at INSERT, so this is a separate rule.
--    Clinic B's line 20000000...0002: qty_ordered=50, qty_received=50, 0
--    returned so far (untouched by section 4's clinic-A-only tests).
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
    v_rejected_id       uuid;
begin
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);

    -- 7a. A rejected return for 40 must not count against the 50-unit
    -- ceiling: a second, non-rejected return for the full 50 must still
    -- succeed even though 40 + 50 > 50.
    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('22222222-2222-2222-2222-222222222222', '10000000-0000-0000-0000-000000000002',
            'ffffffff-0000-0000-0000-000000000002', 'rejected')
    returning id into v_rejected_id;
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
    values (v_rejected_id, '20000000-0000-0000-0000-000000000002', 40);

    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('22222222-2222-2222-2222-222222222222', '10000000-0000-0000-0000-000000000002',
            'ffffffff-0000-0000-0000-000000000002', 'approved');
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
    select id, '20000000-0000-0000-0000-000000000002', 50 from supplier_return
    where purchase_order_id = '10000000-0000-0000-0000-000000000002' and status = 'approved'
    order by requested_at desc limit 1;

    -- 7b. Reinstating the rejected return (flipping it to 'pending') would
    -- push the line to 40 + 50 = 90 against its 50-unit ceiling -- must raise.
    begin
        update supplier_return set status = 'pending' where id = v_rejected_id;
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: reinstating a rejected supplier_return past the ceiling succeeded';
    end if;
end $$;

-- ===========================================================================
-- 8. Cross-tenant FK guard (assert_same_clinic, V10__triggers.sql): a row's
--    OTHER foreign keys must point into its own clinic, not just its own
--    clinic_id column being correct. FK validity checks bypass RLS, so
--    without this trigger the RLS WITH CHECK in section 2 would not catch it.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
    v_clinic_b_membership uuid;
begin
    -- Fetch clinic B's own membership id under ITS session -- RLS hides it
    -- from clinic A's session, same as any other tenant table, so it has to
    -- be read while scoped to B before switching to A for the actual test.
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);
    select id into v_clinic_b_membership from membership limit 1;

    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    -- 8a. Clinic A session, employee_id pointing at clinic B's employee: the
    -- FK is satisfied (the row exists) and clinic_id = A passes RLS, so only
    -- this trigger stops it.
    begin
        insert into daily_record (clinic_id, employee_id, work_date)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000002', '2026-09-02');
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: daily_record.employee_id pointing at another clinic''s employee was accepted';
    end if;

    -- 8b. Same shape via a "created_by" audit column into membership, not
    -- just a primary business FK: stock_movement.created_by from clinic B's
    -- membership must also raise.
    unexpected_success := false;
    begin
        insert into stock_movement (clinic_id, item_id, location, qty_delta, reason, created_by)
        values ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001', 'store', 1, 'adjustment', v_clinic_b_membership);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: stock_movement.created_by pointing at another clinic''s membership was accepted';
    end if;
end $$;

-- ===========================================================================
-- 9. app_user.password_hash is not reachable through a blanket SELECT, only
--    through the SECURITY DEFINER credentials lookup (V9__rls_policies.sql).
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
begin
    -- 9a. A direct column select must be denied by the column-level grant.
    begin
        perform password_hash from app_user limit 1;
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: app_rw could SELECT app_user.password_hash directly';
    end if;
end $$;

do $$
declare
    v_hash text;
begin
    -- 9b. The lookup function (owned by the migration role, so it can see
    -- the column app_rw's own grant excludes) must still work for app_rw.
    select password_hash into v_hash from app_user_credentials_lookup_by_clinic_username('clinic-a', 'owner');
    if v_hash is distinct from 'x' then
        raise exception 'REGRESSION: app_user_credentials_lookup_by_clinic_username did not return the expected password_hash, got %', v_hash;
    end if;
end $$;

do $$
declare
    v_hash text;
begin
    -- 9c. app_rw can create a temp table named app_user (it keeps the
    -- default CREATE TEMP right) -- the lookup function's search_path must
    -- still resolve to the real public.app_user, not this shadow, or an
    -- attacker with just enough access to run arbitrary SQL on the app_rw
    -- connection could hand the login path any password_hash they want.
    create temp table app_user (id uuid, clinic_id uuid, username citext, email citext, password_hash text, status text);
    insert into app_user values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'owner', 'attacker@example.com', 'attacker-controlled-hash', 'active');

    select password_hash into v_hash from app_user_credentials_lookup_by_clinic_username('clinic-a', 'owner');
    if v_hash is distinct from 'x' then
        raise exception 'REGRESSION: app_user_credentials_lookup_by_clinic_username returned % instead of the real app_user.password_hash -- a temp table shadowed it', v_hash;
    end if;

    drop table app_user;
end $$;

-- ===========================================================================
-- 10. Every table carrying its own clinic_id column must have row-level
--     security actually enabled. V9's `alter default privileges` grants
--     full SELECT/INSERT/UPDATE/DELETE to app_rw on any FUTURE table
--     automatically, but RLS itself can't be a default -- a migration that
--     adds a new clinic_id table and forgets `enable row level security`
--     would otherwise be silently wide open to every tenant, with nothing
--     else in this suite able to catch it.
--
--     app_user is the one deliberate exception: V14 gave it a clinic_id
--     column (one account belongs to exactly one clinic), but it stays a
--     platform table like V9 always intended -- reached only through the
--     SECURITY DEFINER pre-auth functions and app_rw's column-level grants,
--     not RLS. Excluded by name, not by "no clinic_id", so a future table
--     that legitimately needs RLS can't hide behind this exclusion by
--     accident.
-- ===========================================================================

do $$
declare
    v_unprotected text;
begin
    select string_agg(c.relname, ', ') into v_unprotected
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and c.relkind = 'r'
      and not c.relrowsecurity
      and c.relname <> 'app_user'
      and exists (
          select 1 from pg_attribute a
          where a.attrelid = c.oid and a.attname = 'clinic_id' and not a.attisdropped
      );
    if v_unprotected is not null then
        raise exception 'REGRESSION: table(s) with a clinic_id column but RLS not enabled: %', v_unprotected;
    end if;
end $$;

-- ===========================================================================
-- 11. Regressions found in the round-2 review of the fixes above: a
--     multi-line reinstate undercounting the ceiling, qty_received able to
--     drop below what's already been returned against it, a cascade delete
--     of a ledger-owning row, and the cross-tenant FK guard's NULL-skip path.
-- ===========================================================================

do $$
declare
    unexpected_success boolean := false;
    v_rejected_id       uuid;
begin
    -- 11a. A return with TWO lines against the same purchase_order_line
    -- (qty_received=10, lines of 5+5) must be reinstated as one 10-unit
    -- total, not checked line-by-line against a baseline that excludes both
    -- of its own lines each time -- that undercounting let a reinstate
    -- through even when the combined total exceeded the ceiling.
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);

    insert into purchase_order_line (id, order_id, item_id, qty_ordered, unit_cost, qty_received) values
        ('20000000-0000-0000-0000-000000000099', '10000000-0000-0000-0000-000000000001',
         'eeeeeeee-0000-0000-0000-000000000001', 20, 50, 10);

    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'rejected')
    returning id into v_rejected_id;
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty) values
        (v_rejected_id, '20000000-0000-0000-0000-000000000099', 5),
        (v_rejected_id, '20000000-0000-0000-0000-000000000099', 5);

    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'approved');
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
    select id, '20000000-0000-0000-0000-000000000099', 5 from supplier_return
    where purchase_order_id = '10000000-0000-0000-0000-000000000001' and status = 'approved'
    order by requested_at desc limit 1;

    -- Reinstating the two-line rejected return would push the line to
    -- 5+5 (reinstated) + 5 (already approved) = 15 against its 10-unit
    -- ceiling -- must raise.
    begin
        update supplier_return set status = 'pending' where id = v_rejected_id;
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: reinstating a multi-line supplier_return past the ceiling succeeded (per-line undercounting)';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
begin
    -- 11b. purchase_order_line.qty_received can't be lowered below what's
    -- already been returned against it. Line ...0099 above has 10 received
    -- and (from 11a) 5 already approved-returned.
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    begin
        update purchase_order_line set qty_received = 3 where id = '20000000-0000-0000-0000-000000000099';
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: lowering qty_received below the already-returned qty succeeded';
    end if;
end $$;

do $$
declare
    v_movement_count integer;
begin
    -- 11c. Deleting an inventory_item with stock_movement history must
    -- cascade through the append-only ledger's own immutability trigger
    -- (forbid_update_delete), not be permanently blocked by it -- only a
    -- DIRECT delete/update on stock_movement should be forbidden.
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);

    insert into inventory_item (id, clinic_id, name, uom, unit_cost) values
        ('99999999-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'Disposable Test Item', 'unit', 1);
    insert into stock_movement (clinic_id, item_id, location, qty_delta, reason) values
        ('22222222-2222-2222-2222-222222222222', '99999999-0000-0000-0000-000000000001', 'store', 5, 'adjustment');

    delete from inventory_item where id = '99999999-0000-0000-0000-000000000001';

    select count(*) into v_movement_count from stock_movement where item_id = '99999999-0000-0000-0000-000000000001';
    if v_movement_count <> 0 then
        raise exception 'REGRESSION: inventory_item delete did not cascade to its stock_movement rows';
    end if;
end $$;

do $$
declare
    unexpected_failure boolean := false;
begin
    -- 11d. assert_same_clinic's NULL-skip branch: an optional cross-tenant FK
    -- column left NULL must not be blocked -- every nullable column in the
    -- guarded spec list (rated_by, approved_by, frozen_by, photo ids, etc.)
    -- depends on this.
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    begin
        insert into daily_record (clinic_id, employee_id, work_date, rated_by)
        values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-03', null);
    exception when others then
        unexpected_failure := true;
    end;
    if unexpected_failure then
        raise exception 'REGRESSION: daily_record insert with rated_by left NULL was rejected by the cross-tenant FK guard';
    end if;
end $$;

-- ===========================================================================
-- 12. Per-clinic auth-mode functions (V14): app_user_credentials_lookup_by_
--     clinic_username (now backstopped by app_user.clinic_id, so app_clinic_id
--     is no longer needed to disambiguate) and app_user_memberships_lookup.
--     Both are SECURITY DEFINER so they bypass RLS and serve the login flow
--     before app.clinic_id is known.
-- ===========================================================================

do $$
declare
    v_hash text;
begin
    -- 12a. The (clinic_slug, username)-keyed lookup function must work for
    -- app_rw and return the expected password_hash for a seeded user. Both
    -- seeded clinics share the username 'owner' yet the query must hit only
    -- Clinic A's row.
    select password_hash into v_hash from app_user_credentials_lookup_by_clinic_username('clinic-a', 'owner');
    if v_hash is distinct from 'x' then
        raise exception 'REGRESSION: app_user_credentials_lookup_by_clinic_username did not return the expected password_hash, got %', v_hash;
    end if;
end $$;

do $$
declare
    v_hash text;
begin
    -- 12b. app_rw can create a temp table named app_user (it keeps the
    -- default CREATE TEMP right) -- the clinic-keyed lookup function's
    -- search_path must still resolve to the real public.app_user, not this
    -- shadow, or an attacker with just enough access to run arbitrary SQL on
    -- the app_rw connection could hand the login path any password_hash they
    -- want.
    create temp table app_user (id uuid, clinic_id uuid, username citext, email citext, password_hash text, status text);
    insert into app_user values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'owner', 'attacker@example.com', 'attacker-controlled-hash', 'active');

    select password_hash into v_hash from app_user_credentials_lookup_by_clinic_username('clinic-a', 'owner');
    if v_hash is distinct from 'x' then
        raise exception 'REGRESSION: app_user_credentials_lookup_by_clinic_username returned % instead of the real app_user.password_hash -- a temp table shadowed it', v_hash;
    end if;

    drop table app_user;
end $$;

do $$
declare
    v_clinic_id uuid;
    v_clinic_name text;
    v_role_code text;
begin
    -- 12c. The membership lookup function returns the correct clinic_id,
    -- clinic_name, and role_code for a seeded user with an active membership.
    select clinic_id, clinic_name, role_code into v_clinic_id, v_clinic_name, v_role_code
    from app_user_memberships_lookup('aaaaaaaa-0000-0000-0000-000000000001');
    if v_clinic_id is distinct from '11111111-1111-1111-1111-111111111111' then
        raise exception 'REGRESSION: app_user_memberships_lookup returned wrong clinic_id: %', v_clinic_id;
    end if;
    if v_clinic_name is distinct from 'Clinic A' then
        raise exception 'REGRESSION: app_user_memberships_lookup returned wrong clinic_name: %', v_clinic_name;
    end if;
    if v_role_code is distinct from 'owner' then
        raise exception 'REGRESSION: app_user_memberships_lookup returned wrong role_code: %', v_role_code;
    end if;
end $$;

-- ===========================================================================
-- 13. Two invariants of the per-clinic account model (V14):
--
--     (a) The same owner email may be reused across different clinics --
--         email uniqueness is scoped by (clinic_id, email), not global, and
--         self-service sign-up is the only path that creates an app_user row
--         (DML on app_user is revoked for app_rw, V9__rls_policies.sql), so
--         this is exercised through signup_clinic_with_owner directly rather
--         than a raw INSERT.
--
--     (b) An account can never hold a membership in a clinic other than its
--         own app_user.clinic_id -- trg_membership_clinic_matches_user must
--         reject it, so the same person working at two clinics is forced
--         into two separate accounts, never one login spanning both.
-- ===========================================================================

do $$
declare
    r1 record;
    r2 record;
begin
    -- 13a. Same email, two brand-new clinics: both sign-ups must succeed and
    -- land on two distinct clinics with two distinct accounts.
    select * into r1 from signup_clinic_with_owner(
        'Multi Clinic Check A', 'multi-clinic-check-a', 'Shared Owner', 'shared-owner-a', 'shared-owner@example.com', 'x');
    select * into r2 from signup_clinic_with_owner(
        'Multi Clinic Check B', 'multi-clinic-check-b', 'Shared Owner', 'shared-owner-b', 'shared-owner@example.com', 'x');

    if r1.clinic_id = r2.clinic_id then
        raise exception 'REGRESSION: signup_clinic_with_owner reused a clinic_id across two sign-ups sharing one email';
    end if;
    if r1.user_id = r2.user_id then
        raise exception 'REGRESSION: signup_clinic_with_owner reused a user_id across two sign-ups sharing one email';
    end if;
end $$;

do $$
declare
    unexpected_success boolean := false;
    v_owner_role_id uuid;
begin
    -- 13b. Clinic A's seeded owner (app_user.clinic_id = clinic A) must not be
    -- insertable as a membership of clinic B -- the guard trigger, not just
    -- application code, is what makes "new account per clinic" true.
    select id into v_owner_role_id from role where code = 'owner';

    -- Scoped to clinic B so the RLS WITH CHECK on membership.clinic_id passes
    -- and the guard trigger is what actually stops the insert, not RLS.
    perform set_config('app.clinic_id', '22222222-2222-2222-2222-222222222222', true);

    begin
        insert into membership (clinic_id, user_id, role_id)
        values ('22222222-2222-2222-2222-222222222222', 'aaaaaaaa-0000-0000-0000-000000000001', v_owner_role_id);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: a membership row was accepted for a clinic that does not match its user''s app_user.clinic_id';
    end if;
end $$;
