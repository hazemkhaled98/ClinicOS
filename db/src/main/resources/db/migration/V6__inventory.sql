-- Inventory. Replaces the single inv:state blob (items, sups, orders, received,
-- returns, invQueue, procs) with normalized tables and an append-only stock ledger.

create type location_kind as enum ('store', 'tray');
create type movement_reason as enum ('receipt', 'issue', 'transfer', 'return', 'adjustment', 'count');
create type po_status as enum ('draft', 'placed', 'received', 'cancelled');
create type return_status as enum ('pending', 'approved', 'rejected');
create type change_request_kind as enum ('edit', 'delete');
create type change_request_status as enum ('pending', 'approved', 'rejected');

create table supplier (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    name            text not null,
    contact         text,
    archived_at     timestamptz
);

create index idx_supplier_clinic on supplier (clinic_id);

create table inventory_item (
    id                      uuid primary key default gen_random_uuid(),
    clinic_id               uuid not null references clinic (id) on delete cascade,
    name                    text not null,
    uom                     text not null,
    units_per_pack          integer check (units_per_pack >= 1),
    unit_cost               numeric(10,2) not null check (unit_cost >= 0),
    preferred_supplier_id   uuid references supplier (id),
    expiry_date             date,
    store_alert             integer check (store_alert >= 0),
    tray_alert              integer check (tray_alert >= 0),
    archived_at             timestamptz
);

create index idx_inventory_item_clinic on inventory_item (clinic_id);

-- Append-only ledger: on-hand quantity per (item, location) is sum(qty_delta).
-- Locations are the fixed store/tray split the app has always had -- not an
-- open table, since nothing needs a third pool.
-- ponytail: balance computed by aggregate; add a materialized stock_balance table
-- if the sum gets slow past ~1e6 movements per clinic.
create table stock_movement (
    id          uuid primary key default gen_random_uuid(),
    clinic_id   uuid not null references clinic (id) on delete cascade,
    item_id     uuid not null references inventory_item (id) on delete cascade,
    location    location_kind not null,
    qty_delta   numeric(12,2) not null,
    reason      movement_reason not null,
    ref_type    text,        -- e.g. 'purchase_order', 'procedure_case', 'supplier_return'
    ref_id      uuid,
    created_by  uuid references membership (id),
    created_at  timestamptz not null default now()
);

create index idx_stock_movement_clinic on stock_movement (clinic_id, item_id, location);
create index idx_stock_movement_ref on stock_movement (ref_type, ref_id);

create table purchase_order (
    id                  uuid primary key default gen_random_uuid(),
    clinic_id           uuid not null references clinic (id) on delete cascade,
    supplier_id         uuid not null references supplier (id),
    status              po_status not null default 'draft',
    placed_by           uuid references membership (id),
    placed_at           timestamptz,
    received_at         timestamptz,
    invoice_photo_id    uuid -- FK to attachment added in V8
);

create index idx_purchase_order_clinic on purchase_order (clinic_id, supplier_id);

create table purchase_order_line (
    id              uuid primary key default gen_random_uuid(),
    order_id        uuid not null references purchase_order (id) on delete cascade,
    item_id         uuid not null references inventory_item (id),
    qty_ordered     numeric(12,2) not null check (qty_ordered > 0),
    unit_cost       numeric(10,2) not null check (unit_cost >= 0),
    qty_received    numeric(12,2) not null default 0 check (qty_received >= 0),
    check (qty_received <= qty_ordered)
);

create index idx_po_line_order on purchase_order_line (order_id);

create table supplier_return (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    purchase_order_id uuid not null references purchase_order (id),
    supplier_id     uuid not null references supplier (id),
    requested_by    uuid references membership (id),
    requested_at    timestamptz not null default now(),
    status          return_status not null default 'pending',
    approved_by     uuid references membership (id),
    approved_at     timestamptz
);

create index idx_supplier_return_clinic on supplier_return (clinic_id);

create table supplier_return_line (
    id                          uuid primary key default gen_random_uuid(),
    supplier_return_id          uuid not null references supplier_return (id) on delete cascade,
    purchase_order_line_id      uuid not null references purchase_order_line (id),
    qty                         numeric(12,2) not null check (qty > 0)
);

create index idx_return_line_return on supplier_return_line (supplier_return_id);
create index idx_return_line_po_line on supplier_return_line (purchase_order_line_id);

-- Proposed but unapplied change to a stock item, awaiting manager approval (BR-G26).
create table inventory_change_request (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    kind            change_request_kind not null,
    item_id         uuid references inventory_item (id),
    payload         jsonb,   -- proposed new values for 'edit'; null for 'delete'
    requested_by    uuid references membership (id),
    requested_at    timestamptz not null default now(),
    status          change_request_status not null default 'pending',
    decided_by      uuid references membership (id),
    decided_at      timestamptz
);

create index idx_inv_change_request_clinic on inventory_change_request (clinic_id, status);
