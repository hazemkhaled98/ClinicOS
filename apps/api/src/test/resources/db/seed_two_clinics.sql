-- Seeds two clinics with deliberately overlapping names/data, to prove
-- tenant-scoped uniqueness constraints don't collide across clinics and RLS
-- isolates them correctly. Run as a superuser/owner role (RLS doesn't apply
-- to table owners), then run schema_checks.sql as app_rw.

insert into clinic (id, name, slug) values
    ('11111111-1111-1111-1111-111111111111', 'Clinic A', 'clinic-a'),
    ('22222222-2222-2222-2222-222222222222', 'Clinic B', 'clinic-b');

insert into clinic_settings (clinic_id) values
    ('11111111-1111-1111-1111-111111111111'),
    ('22222222-2222-2222-2222-222222222222');

-- Both clinics share the username 'owner' to prove the new per-clinic
-- (clinic_id, username) uniqueness lets the same login name live in isolation
-- on each tenant; the distinct emails stay on separate rows.
insert into app_user (id, clinic_id, email, password_hash, full_name, username) values
    ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'owner-a@example.com', 'x', 'Owner A', 'owner'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'owner-b@example.com', 'x', 'Owner B', 'owner');

insert into membership (clinic_id, user_id, role_id)
select '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-0000-0000-0000-000000000001', id from role where code = 'owner';
insert into membership (clinic_id, user_id, role_id)
select '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-0000-0000-0000-000000000002', id from role where code = 'owner';

-- Same employee name in both clinics -- must not collide (no cross-clinic unique constraint).
insert into employee (id, clinic_id, name, staff_role, base_pay, max_incentive) values
    ('bbbbbbbb-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Sara Ahmed', 'assistant', 4000, 1000),
    ('bbbbbbbb-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Sara Ahmed', 'assistant', 4200, 1100);

-- Same task name in both clinics.
insert into task_definition (id, clinic_id, staff_role, name, dimension, frequency, display_order) values
    ('cccccccc-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'assistant', 'Sterilize tray', 'fanni', 'daily', 1),
    ('cccccccc-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'assistant', 'Sterilize tray', 'fanni', 'daily', 1);

-- Inventory + a receive/issue/return chain for the stock_movement sum check.
insert into inventory_item (id, clinic_id, name, uom, unit_cost) values
    ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Gloves box', 'box', 50);

insert into supplier (id, clinic_id, name) values
    ('ffffffff-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'MedSupply Co');

insert into purchase_order (id, clinic_id, supplier_id, status, placed_at, received_at) values
    ('10000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     'ffffffff-0000-0000-0000-000000000001', 'received', now(), now());

insert into purchase_order_line (id, order_id, item_id, qty_ordered, unit_cost, qty_received) values
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
     'eeeeeeee-0000-0000-0000-000000000001', 100, 50, 100);

-- receipt: +100
insert into stock_movement (clinic_id, item_id, location, qty_delta, reason, ref_type, ref_id) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'store', 100, 'receipt', 'purchase_order', '10000000-0000-0000-0000-000000000001');

-- issue: -20
insert into stock_movement (clinic_id, item_id, location, qty_delta, reason) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'store', -20, 'issue');

-- return: -10 (within the 100 received, well under the ceiling)
insert into supplier_return (id, clinic_id, purchase_order_id, supplier_id, status) values
    ('30000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     '10000000-0000-0000-0000-000000000001', 'ffffffff-0000-0000-0000-000000000001', 'approved');
insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty) values
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 10);
insert into stock_movement (clinic_id, item_id, location, qty_delta, reason, ref_type, ref_id) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'store', -10, 'return', 'supplier_return', '30000000-0000-0000-0000-000000000001');

-- Expected on-hand for eeeeeeee.../store = 100 - 20 - 10 = 70

-- A frozen evaluation snapshot for the trigger check.
insert into evaluation_snapshot (id, clinic_id, employee_id, period_month, final_score) values
    ('40000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     'bbbbbbbb-0000-0000-0000-000000000001', '2026-08-01', 88.5);

insert into evaluation_component (snapshot_id, category, raw_score, weight, included) values
    ('40000000-0000-0000-0000-000000000001', 'completion', 90, 30, true),
    ('40000000-0000-0000-0000-000000000001', 'fanni', 85, 20, true),
    ('40000000-0000-0000-0000-000000000001', 'solooki', 88, 20, true),
    ('40000000-0000-0000-0000-000000000001', 'ibda3', 92, 10, true),
    ('40000000-0000-0000-0000-000000000001', 'attendance', 95, 20, true);

-- A two-hop prep checklist chain, to prove the prep_item RLS policy (the one
-- that doesn't fit the single-parent loop) actually isolates by tenant.
insert into prep_checklist (id, clinic_id, name, status) values
    ('50000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Root Canal Setup', 'approved');
insert into prep_section (id, checklist_id, title, display_order) values
    ('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', 'Instruments', 1);
insert into prep_item (id, section_id, name, display_order) values
    ('70000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 'Files', 1);

-- Clinic B's own inventory chain, disjoint from A's, so cross-tenant checks
-- have real B-side rows to prove are invisible from A's session (and vice
-- versa) -- not just an empty result that could mean "isolated" or "broken".
insert into supplier (id, clinic_id, name) values
    ('ffffffff-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Clinic B Supplies');

insert into inventory_item (id, clinic_id, name, uom, unit_cost) values
    ('eeeeeeee-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Gloves box', 'box', 55);

insert into purchase_order (id, clinic_id, supplier_id, status, placed_at, received_at) values
    ('10000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222',
     'ffffffff-0000-0000-0000-000000000002', 'received', now(), now());

insert into purchase_order_line (id, order_id, item_id, qty_ordered, unit_cost, qty_received) values
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
     'eeeeeeee-0000-0000-0000-000000000002', 50, 55, 50);

insert into stock_movement (clinic_id, item_id, location, qty_delta, reason, ref_type, ref_id) values
    ('22222222-2222-2222-2222-222222222222', 'eeeeeeee-0000-0000-0000-000000000002',
     'store', 50, 'receipt', 'purchase_order', '10000000-0000-0000-0000-000000000002');

-- An academy unit, for the correct_index-in-range check.
insert into academy_unit (id, clinic_id, applies_to, title) values
    ('80000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'core', 'Sterilization Basics');
