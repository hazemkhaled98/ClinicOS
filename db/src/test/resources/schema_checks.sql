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
        insert into employee (clinic_id, name, staff_role, base_pay, max_incentive)
        values ('22222222-2222-2222-2222-222222222222', 'Cross Tenant', 'assistant', 100, 10);
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

    -- 4a. Remaining receivable is 90; returning 95 must raise.
    begin
        insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
        values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
                'ffffffff-0000-0000-0000-000000000001', 'pending');
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
    -- "not found" guard, which sees the line as absent under A's RLS.
    perform set_config('app.clinic_id', '11111111-1111-1111-1111-111111111111', true);
    begin
        insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
        values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
                'ffffffff-0000-0000-0000-000000000001', 'pending');
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
        insert into employee (clinic_id, name, staff_role, base_pay, max_incentive, custom_shift)
        values ('11111111-1111-1111-1111-111111111111', 'Bad Shift Employee', 'assistant', 100, 10, true);
        unexpected_success := true;
    exception when others then null;
    end;
    if unexpected_success then
        raise exception 'REGRESSION: custom_shift=true with no shift_start/shift_end was accepted';
    end if;

    -- 5f. custom_shift = false with shift_start/shift_end set must also raise.
    unexpected_success := false;
    begin
        insert into employee (clinic_id, name, staff_role, base_pay, max_incentive, custom_shift, shift_start, shift_end)
        values ('11111111-1111-1111-1111-111111111111', 'Bad Shift Employee 2', 'assistant', 100, 10, false, '08:00', '16:00');
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
end $$;
