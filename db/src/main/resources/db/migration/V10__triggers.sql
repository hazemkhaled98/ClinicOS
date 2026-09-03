-- Business-rule triggers: rules that must hold regardless of which application
-- code path writes the row, so they live in the database, not only in services.

-- BR-G05: a frozen month is write-once. Once evaluation_snapshot.unlocked_at is
-- null, no column may change; only setting unlocked_at (the explicit unlock
-- action) opens it back up for one subsequent update.
create or replace function evaluation_snapshot_block_frozen_update()
returns trigger as $$
begin
    -- Still frozen unless this very update is the one setting unlocked_at.
    if old.unlocked_at is null and new.unlocked_at is null then
        raise exception 'evaluation_snapshot % for period % is frozen; unlock it before editing', old.id, old.period_month;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_evaluation_snapshot_frozen
    before update on evaluation_snapshot
    for each row
    execute function evaluation_snapshot_block_frozen_update();

-- BR-G27: total returned quantity against a purchase order line can never
-- exceed what was actually received on that line.
create or replace function supplier_return_line_check_ceiling()
returns trigger as $$
declare
    received     numeric(12,2);
    already_returned numeric(12,2);
begin
    select qty_received into received
    from purchase_order_line
    where id = new.purchase_order_line_id;

    select coalesce(sum(qty), 0) into already_returned
    from supplier_return_line
    where purchase_order_line_id = new.purchase_order_line_id
      and id <> coalesce(new.id, '00000000-0000-0000-0000-000000000000'::uuid);

    if already_returned + new.qty > received then
        raise exception 'return qty % exceeds remaining receivable qty % (received %, already returned %)',
            new.qty, received - already_returned, received, already_returned;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_supplier_return_line_ceiling
    before insert or update on supplier_return_line
    for each row
    execute function supplier_return_line_check_ceiling();
