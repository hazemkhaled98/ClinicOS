-- Run as app_rw, after seed_two_clinics.sql. Each block should behave exactly
-- as its comment says; a psql \set ON_ERROR_STOP is recommended for the
-- "must succeed" statements, and the "must raise" statements are expected to
-- print an ERROR and abort their own transaction (run each in its own
-- `begin; ... rollback;` when scripting this, as done below).

-- 1. RLS isolation: scoped to Clinic A, only Clinic A's employees are visible.
-- (SET LOCAL only holds for the enclosing transaction, so this is wrapped.)
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    select count(*) as should_be_1 from employee where name = 'Sara Ahmed'; -- expect 1, not 2
commit;

-- 2. Cross-tenant insert is rejected by the RLS with-check clause.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    -- must raise: row's clinic_id (B) doesn't match the session's clinic_id (A)
    insert into employee (clinic_id, name, staff_role, base_pay, max_incentive)
    values ('22222222-2222-2222-2222-222222222222', 'Cross Tenant', 'assistant', 100, 10);
rollback;

-- 3. Frozen snapshot immutability (BR-G05): update while unlocked_at is null must raise.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    -- must raise
    update evaluation_snapshot set final_score = 99 where id = '40000000-0000-0000-0000-000000000001';
rollback;

-- 4. Unlock then edit: must succeed once unlocked_at is set.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    update evaluation_snapshot set unlocked_at = now() where id = '40000000-0000-0000-0000-000000000001';
    update evaluation_snapshot set final_score = 90 where id = '40000000-0000-0000-0000-000000000001';
    select final_score from evaluation_snapshot where id = '40000000-0000-0000-0000-000000000001'; -- expect 90.00
rollback;

-- 5. Return-quantity ceiling (BR-G27): 100 received, 10 already returned ->
-- returning 95 more (total 105) must raise.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    insert into supplier_return (clinic_id, purchase_order_id, supplier_id, status)
    values ('11111111-1111-1111-1111-111111111111', '10000000-0000-0000-0000-000000000001',
            'ffffffff-0000-0000-0000-000000000001', 'pending');
    -- must raise
    insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty)
    select id, '20000000-0000-0000-0000-000000000001', 95 from supplier_return
    where purchase_order_id = '10000000-0000-0000-0000-000000000001' and status = 'pending';
rollback;

-- 6. Rating range and per-day uniqueness on daily_record.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    -- must raise: fanni out of 1..5
    insert into daily_record (clinic_id, employee_id, work_date, fanni)
    values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 6);
rollback;

begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    insert into daily_record (clinic_id, employee_id, work_date, fanni)
    values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 5);
    -- must raise: duplicate (employee_id, work_date)
    insert into daily_record (clinic_id, employee_id, work_date, fanni)
    values ('11111111-1111-1111-1111-111111111111', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-09-01', 4);
rollback;

-- 7. Stock movement sum matches expected on-hand after receipt -> issue -> return.
begin;
    set local app.clinic_id = '11111111-1111-1111-1111-111111111111';
    select sum(qty_delta) as should_be_70
    from stock_movement
    where item_id = 'eeeeeeee-0000-0000-0000-000000000001'
      and location_id = 'dddddddd-0000-0000-0000-000000000001';
commit;
