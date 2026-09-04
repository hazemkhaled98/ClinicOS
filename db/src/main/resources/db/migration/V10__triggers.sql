-- Business-rule triggers: rules that must hold regardless of which application
-- code path writes the row, so they live in the database, not only in services.

-- BR-G05: a frozen month is write-once. Once evaluation_snapshot.unlocked_at is
-- null, no column may change or the row may be deleted; only setting
-- unlocked_at (the explicit unlock action) opens it up. That unlock is spent
-- by the very next edit -- the edit statement itself re-nulls unlocked_at, so
-- a second edit without another explicit unlock is blocked again. An unlock
-- and its edit must be two separate statements (see schema_checks.sql) --
-- combining them into one UPDATE leaves the row unlocked rather than spending
-- the unlock, which is an accepted edge case, not a supported flow.
create or replace function evaluation_snapshot_block_frozen_write()
returns trigger as $$
begin
    if tg_op = 'DELETE' then
        if old.unlocked_at is null then
            raise exception 'evaluation_snapshot % for period % is frozen; unlock it before deleting', old.id, old.period_month;
        end if;
        return old;
    end if;

    -- UPDATE
    if old.unlocked_at is null and new.unlocked_at is null then
        raise exception 'evaluation_snapshot % for period % is frozen; unlock it before editing', old.id, old.period_month;
    elsif old.unlocked_at is not null and new.unlocked_at is not null then
        -- This edit spends the unlock: re-freeze immediately after it applies.
        new.unlocked_at := null;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_evaluation_snapshot_frozen
    before update or delete on evaluation_snapshot
    for each row
    execute function evaluation_snapshot_block_frozen_write();

-- Snapshot components inherit the parent snapshot's frozen state. Insert is
-- unguarded because components are written once, at freeze time, when the
-- snapshot is (by definition) still frozen -- only later update/delete needs
-- the parent's unlock.
create or replace function evaluation_component_block_when_frozen()
returns trigger as $$
declare
    parent_unlocked boolean;
    parent_id       uuid := coalesce(new.snapshot_id, old.snapshot_id);
begin
    select (unlocked_at is not null) into parent_unlocked
    from evaluation_snapshot
    where id = parent_id;

    if not parent_unlocked then
        raise exception 'evaluation_component for snapshot % is frozen; unlock the snapshot before editing', parent_id;
    end if;

    return coalesce(new, old);
end;
$$ language plpgsql;

create trigger trg_evaluation_component_frozen
    before update or delete on evaluation_component
    for each row
    execute function evaluation_component_block_when_frozen();

-- BR-G27: total returned quantity against a purchase order line can never
-- exceed what was actually received on that line, minus anything already
-- returned. Only non-rejected returns count as consumed quantity -- a
-- rejected return never happened. supplier_return_line rows are immutable
-- (see trg_supplier_return_line_immutable below), so this only needs to
-- guard INSERT.
create or replace function supplier_return_line_check_ceiling()
returns trigger as $$
declare
    received          numeric(12,2);
    already_returned  numeric(12,2);
begin
    select qty_received into received
    from purchase_order_line
    where id = new.purchase_order_line_id
    for update;

    if not found then
        raise exception 'purchase_order_line % not found', new.purchase_order_line_id;
    end if;

    select coalesce(sum(rl.qty), 0) into already_returned
    from supplier_return_line rl
    join supplier_return r on r.id = rl.supplier_return_id
    where rl.purchase_order_line_id = new.purchase_order_line_id
      and r.status <> 'rejected';

    if already_returned + new.qty > received then
        raise exception 'return qty % exceeds remaining receivable qty % (received %, already returned %)',
            new.qty, received - already_returned, received, already_returned;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_supplier_return_line_ceiling
    before insert on supplier_return_line
    for each row
    execute function supplier_return_line_check_ceiling();

-- Rows that are supposed to be append-only ledgers must actually be
-- unappendable-to after the fact: nothing about their column grants or RLS
-- policies stops an UPDATE/DELETE otherwise (app_rw has both).
create or replace function forbid_update_delete()
returns trigger as $$
begin
    raise exception '% is append-only; % is not allowed on row %', tg_table_name, tg_op, coalesce(old.id, new.id);
end;
$$ language plpgsql;

create trigger trg_stock_movement_immutable
    before update or delete on stock_movement
    for each row
    execute function forbid_update_delete();

create trigger trg_supplier_return_line_immutable
    before update or delete on supplier_return_line
    for each row
    execute function forbid_update_delete();
