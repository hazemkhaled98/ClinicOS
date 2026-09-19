-- 7b legacy-gap extras (backlog #6, #7): supplier contact/rating/lead info and
-- per-receipt-line lot number + delivery cost. Both tables already carry
-- clinic_id and are RLS + cross-tenant-FK guarded (V9:111,V9:146,V25:67), so
-- no new policies or guards are needed.

alter table supplier
    add column whatsapp   text,
    add column lead_days  integer check (lead_days >= 0),
    add column rating     numeric(2,1) check (rating between 0 and 5);

alter table purchase_order_line
    add column lot_number     text,
    add column delivery_cost  numeric(10,2) not null default 0 check (delivery_cost >= 0);