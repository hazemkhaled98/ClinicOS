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

insert into app_user (id, email, password_hash, full_name) values
    ('aaaaaaaa-0000-0000-0000-000000000001', 'owner-a@example.com', 'x', 'Owner A'),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'owner-b@example.com', 'x', 'Owner B');

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
insert into stock_location (id, clinic_id, name, kind) values
    ('dddddddd-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Main Store', 'store');

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
insert into stock_movement (clinic_id, item_id, location_id, qty_delta, reason, ref_type, ref_id) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'dddddddd-0000-0000-0000-000000000001', 100, 'receipt', 'purchase_order', '10000000-0000-0000-0000-000000000001');

-- issue: -20
insert into stock_movement (clinic_id, item_id, location_id, qty_delta, reason) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'dddddddd-0000-0000-0000-000000000001', -20, 'issue');

-- return: -10 (within the 100 received, well under the ceiling)
insert into supplier_return (id, clinic_id, purchase_order_id, supplier_id, status) values
    ('30000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     '10000000-0000-0000-0000-000000000001', 'ffffffff-0000-0000-0000-000000000001', 'approved');
insert into supplier_return_line (supplier_return_id, purchase_order_line_id, qty) values
    ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 10);
insert into stock_movement (clinic_id, item_id, location_id, qty_delta, reason, ref_type, ref_id) values
    ('11111111-1111-1111-1111-111111111111', 'eeeeeeee-0000-0000-0000-000000000001',
     'dddddddd-0000-0000-0000-000000000001', -10, 'return', 'supplier_return', '30000000-0000-0000-0000-000000000001');

-- Expected on-hand for eeeeeeee.../dddddddd... = 100 - 20 - 10 = 70

-- A frozen evaluation snapshot for the trigger check.
insert into evaluation_snapshot (id, clinic_id, employee_id, period_month, final_score, weights) values
    ('40000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
     'bbbbbbbb-0000-0000-0000-000000000001', '2026-08-01', 88.5, '{"completion":30,"fanni":20,"solooki":20,"ibda3":10,"attendance":20}');
