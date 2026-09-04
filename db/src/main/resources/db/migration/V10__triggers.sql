-- Business-rule triggers: rules that must hold regardless of which application
-- code path writes the row, so they live in the database, not only in services.

-- BR-G05: a frozen month is write-once. Once evaluation_snapshot.unlocked_at is
-- null, no column may change or the row may be deleted; only setting
-- unlocked_at (the explicit unlock action) opens it up. That unlock is spent
-- by the very next edit -- the edit statement itself re-nulls unlocked_at, so
-- a second edit without another explicit unlock is blocked again. An unlock
-- and its edit must be two separate statements: the unlock branch below
-- rejects any other column changing in the same UPDATE as the one that sets
-- unlocked_at, so combining them can't silently leave the row permanently
-- unlocked (a prior version of this trigger let exactly that through).
create or replace function evaluation_snapshot_block_frozen_write()
returns trigger as $$
begin
    if tg_op = 'DELETE' then
        -- A DELETE cascaded in from clinic/employee removal must go through
        -- even while frozen, same as the append-only ledger tables below --
        -- otherwise a single frozen snapshot permanently blocks deleting the
        -- employee or clinic it belongs to.
        if pg_trigger_depth() > 1 then
            return old;
        end if;
        if old.unlocked_at is null then
            raise exception 'evaluation_snapshot % for period % is frozen; unlock it before deleting', old.id, old.period_month;
        end if;
        return old;
    end if;

    -- UPDATE
    if old.unlocked_at is null and new.unlocked_at is null then
        raise exception 'evaluation_snapshot % for period % is frozen; unlock it before editing', old.id, old.period_month;
    elsif old.unlocked_at is not null and new.unlocked_at is not null then
        -- This edit spends the unlock: re-freeze immediately after it
        -- applies, and clear unlocked_by with it -- a frozen row shouldn't
        -- carry a stale "who last unlocked me" once it's frozen again.
        new.unlocked_at := null;
        new.unlocked_by := null;
    elsif old.unlocked_at is null and new.unlocked_at is not null then
        -- The unlock action itself. Must be its own statement -- nothing
        -- else about the row may change alongside it, or the row comes out
        -- of this trigger unlocked AND already edited in one shot. Diffing
        -- the two rows with unlocked_at/unlocked_by stripped out (rather
        -- than a hand-maintained list of the OTHER columns) means a future
        -- column added to this table is covered automatically instead of
        -- silently slipping through this check unguarded.
        if (to_jsonb(new) - 'unlocked_at' - 'unlocked_by') is distinct from (to_jsonb(old) - 'unlocked_at' - 'unlocked_by') then
            raise exception 'evaluation_snapshot % must be unlocked in its own statement, separate from any edit', old.id;
        end if;
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
    -- A DELETE cascaded in from the parent snapshot's own deletion runs
    -- after the parent row is already gone (FK cascade fires as an AFTER
    -- DELETE trigger on the parent, so by the time this row-level trigger
    -- runs, the parent lookup below would find nothing even though nothing
    -- is wrong) -- same cascade-vs-direct distinction as forbid_update_delete.
    if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
        return old;
    end if;

    -- `for update` closes the same race the ceiling check below guards
    -- against: without it, a concurrent re-freeze of the parent could commit
    -- between this read and this statement's commit, letting an edit through
    -- against a snapshot that's frozen by the time either transaction ends.
    select (unlocked_at is not null) into parent_unlocked
    from evaluation_snapshot
    where id = parent_id
    for update;

    -- A missing parent must fail loud, not fail open: checking the boolean
    -- variable directly (NULL only when no row was found, since
    -- evaluation_snapshot.unlocked_at itself may legitimately be NULL and
    -- that's already folded into the `is not null` comparison above) beats
    -- relying on FOUND, which dynamic EXECUTE elsewhere in this file proved
    -- isn't reliably set.
    if parent_unlocked is null then
        raise exception 'evaluation_snapshot % not found', parent_id;
    end if;

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

-- The ceiling check above only runs at INSERT time and excludes rejected
-- returns from the running total. Reinstating a rejected return (flipping its
-- status back to pending/approved) changes that total without touching
-- supplier_return_line at all, so it needs its own re-check against every
-- line the return carries.
create or replace function supplier_return_reinstated_recheck_ceiling()
returns trigger as $$
declare
    line             record;
    received         numeric(12,2);
    already_returned numeric(12,2);
begin
    if old.status <> 'rejected' or new.status = 'rejected' then
        return new;
    end if;

    -- Grouped by purchase_order_line_id, not one row per supplier_return_line:
    -- a single return can carry more than one line against the same PO line
    -- (no constraint stops it), and checking each one against the ceiling
    -- independently -- adding only that one row's qty back in -- undercounts
    -- whenever a return has two or more lines on the same PO line.
    for line in
        select purchase_order_line_id, sum(qty) as qty
        from supplier_return_line
        where supplier_return_id = new.id
        group by purchase_order_line_id
    loop
        select qty_received into received
        from purchase_order_line
        where id = line.purchase_order_line_id
        for update;

        if not found then
            raise exception 'purchase_order_line % not found', line.purchase_order_line_id;
        end if;

        -- This row's own status is still its OLD value ('rejected') from
        -- this query's point of view -- a BEFORE trigger runs before the
        -- update is applied -- so the filter excludes this return's own
        -- lines from the sum, and line.qty (from the loop, not the sum) is
        -- what has to be added back in to get the post-reinstate total.
        select coalesce(sum(rl.qty), 0) into already_returned
        from supplier_return_line rl
        join supplier_return r on r.id = rl.supplier_return_id
        where rl.purchase_order_line_id = line.purchase_order_line_id
          and r.status <> 'rejected';

        if already_returned + line.qty > received then
            raise exception 'reinstating supplier_return % exceeds remaining receivable qty % on purchase_order_line % (received %, already returned %, reinstating %)',
                new.id, received - already_returned, line.purchase_order_line_id, received, already_returned, line.qty;
        end if;
    end loop;

    return new;
end;
$$ language plpgsql;

create trigger trg_supplier_return_reinstated_recheck
    before update of status on supplier_return
    for each row
    execute function supplier_return_reinstated_recheck_ceiling();

-- The ceiling is `returns <= qty_received`, but nothing so far stops the
-- OTHER side of that comparison from moving: qty_received can be freely
-- lowered after returns have already been approved against it, breaking the
-- invariant just as surely as raising the returns above it would.
create or replace function purchase_order_line_check_ceiling_on_receipt_change()
returns trigger as $$
declare
    already_returned numeric(12,2);
begin
    select coalesce(sum(rl.qty), 0) into already_returned
    from supplier_return_line rl
    join supplier_return r on r.id = rl.supplier_return_id
    where rl.purchase_order_line_id = new.id
      and r.status <> 'rejected';

    if already_returned > new.qty_received then
        raise exception 'purchase_order_line % qty_received (%) would fall below its already-returned qty (%)',
            new.id, new.qty_received, already_returned;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_purchase_order_line_receipt_ceiling
    before update of qty_received on purchase_order_line
    for each row
    execute function purchase_order_line_check_ceiling_on_receipt_change();

-- Rows that are supposed to be append-only ledgers must actually be
-- unappendable-to after the fact: nothing about their column grants or RLS
-- policies stops an UPDATE/DELETE otherwise (app_rw has both).
--
-- A direct top-level DELETE must still be forbidden, but a DELETE cascaded
-- in from a parent's `on delete cascade` (removing the clinic or inventory
-- item this ledger row belongs to) must not be blocked by it, or a clinic/
-- item can never be deleted once it has any ledger history. Postgres runs
-- FK cascade through the referenced row's own trigger machinery, so a
-- cascaded delete's row-level BEFORE DELETE trigger sees pg_trigger_depth()
-- one level deeper than a plain top-level delete does (verified: depth 1 for
-- `delete from child`, depth 2 when it cascades in from `delete from
-- parent`) -- that's the distinction this checks.
create or replace function forbid_update_delete()
returns trigger as $$
begin
    if tg_op = 'DELETE' and pg_trigger_depth() > 1 then
        return old;
    end if;
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

-- Cross-tenant FK guard. RLS's WITH CHECK only validates a row's own
-- clinic_id column -- it says nothing about what a row's OTHER foreign keys
-- point at, and FK referential-integrity checks themselves run under a
-- privileged snapshot that bypasses RLS. Without this, a session scoped to
-- clinic A could insert e.g. a daily_record with clinic_id = A but
-- employee_id pointing at clinic B's employee: the FK is satisfied (the row
-- exists) and the RLS check is satisfied (clinic_id = A), so nothing stops
-- it, and the row is now permanently mis-attributed across tenants.
--
-- One generic trigger function, parameterized per (table, fk_column,
-- referenced_table) the same way V9's RLS policy loops are, rather than a
-- bespoke function per column -- every one of these checks is the same
-- shape: does the referenced row's clinic_id match this row's own.
--
-- Scope: covers every table that carries its own clinic_id column directly
-- (V9's "direct_tables") and has a second FK into another clinic-scoped
-- table. Child tables that have no clinic_id column of their own (scoped via
-- a single parent join, e.g. purchase_order_line.item_id, procedure_bom.item_id,
-- daily_task_completion.task_definition_id, prep_run_item.prep_item_id,
-- performance_override.set_by) would need the same treatment via a two-hop
-- comparison against their own parent's clinic_id -- left as a follow-up,
-- smaller in number and lower exposure than the direct-table case covered
-- here.
create or replace function assert_same_clinic()
returns trigger as $$
declare
    fk_column  text := tg_argv[0];
    ref_table  text := tg_argv[1];
    fk_value   uuid;
    ref_clinic uuid;
begin
    execute format('select ($1).%I', fk_column) into fk_value using new;
    if fk_value is null then
        return new;
    end if;

    -- `not found` isn't reliable after a dynamic EXECUTE ... INTO, so a
    -- missing row is detected the other way: every referenced table here has
    -- clinic_id declared not null, so ref_clinic staying NULL only happens
    -- when the row itself doesn't exist.
    --
    -- ref_table is itself one of V9's RLS-protected tables, so this trigger
    -- (running as app_rw, same as any other statement) can never actually
    -- SEE a row belonging to a different clinic in the first place -- it
    -- comes back as zero rows, same as if the id didn't exist at all. That
    -- makes the "cross-tenant reference" branch below unreachable for a
    -- normal app_rw session; the message below says so rather than claiming
    -- a distinction this trigger can't actually observe. The branch stays as
    -- defense in depth for any future caller that isn't RLS-scoped (a
    -- privileged migration/backfill role, say).
    execute format('select clinic_id from %I where id = $1', ref_table) into ref_clinic using fk_value;
    if ref_clinic is null then
        raise exception '% % referenced by %.% not found (or belongs to a different clinic, which looks identical under RLS)', ref_table, fk_value, tg_table_name, fk_column;
    end if;

    if ref_clinic is distinct from new.clinic_id then
        raise exception 'cross-tenant reference: %.% (%) points to a % row in a different clinic', tg_table_name, fk_column, fk_value, ref_table;
    end if;

    return new;
end;
$$ language plpgsql;

do $$
declare
    specs text[][] := array[
        ['membership', 'employee_id', 'employee'],
        ['daily_record', 'employee_id', 'employee'],
        ['daily_record', 'rated_by', 'membership'],
        ['self_check', 'employee_id', 'employee'],
        ['task_assignment', 'employee_id', 'employee'],
        ['task_assignment', 'approved_by', 'membership'],
        ['task_assignment', 'proof_photo_id', 'attachment'],
        ['task_assignment', 'verification_photo_id', 'attachment'],
        ['evaluation_snapshot', 'employee_id', 'employee'],
        ['evaluation_snapshot', 'frozen_by', 'membership'],
        ['evaluation_snapshot', 'unlocked_by', 'membership'],
        ['operations_volume', 'recorded_by', 'membership'],
        ['academy_step_submission', 'employee_id', 'employee'],
        ['academy_step_submission', 'unit_id', 'academy_unit'],
        ['academy_step_submission', 'verified_by', 'membership'],
        ['academy_step_submission', 'photo_id', 'attachment'],
        ['academy_exam_attempt', 'employee_id', 'employee'],
        ['prep_checklist', 'approved_by', 'membership'],
        ['prep_run', 'checklist_id', 'prep_checklist'],
        ['prep_run', 'employee_id', 'employee'],
        ['inventory_item', 'preferred_supplier_id', 'supplier'],
        ['stock_movement', 'item_id', 'inventory_item'],
        ['stock_movement', 'created_by', 'membership'],
        ['purchase_order', 'supplier_id', 'supplier'],
        ['purchase_order', 'placed_by', 'membership'],
        ['purchase_order', 'invoice_photo_id', 'attachment'],
        ['supplier_return', 'purchase_order_id', 'purchase_order'],
        ['supplier_return', 'supplier_id', 'supplier'],
        ['supplier_return', 'requested_by', 'membership'],
        ['supplier_return', 'approved_by', 'membership'],
        ['inventory_change_request', 'item_id', 'inventory_item'],
        ['inventory_change_request', 'requested_by', 'membership'],
        ['inventory_change_request', 'decided_by', 'membership'],
        ['procedure_case', 'procedure_id', 'procedure'],
        ['procedure_case', 'employee_id', 'employee'],
        ['attachment', 'uploaded_by', 'membership'],
        ['activity_log', 'actor_membership_id', 'membership'],
        ['notification', 'recipient_membership_id', 'membership']
    ];
begin
    for i in 1 .. array_upper(specs, 1) loop
        execute format(
            'create trigger trg_%s_%s_same_clinic before insert or update on %I for each row execute function assert_same_clinic(%L, %L)',
            specs[i][1], specs[i][2], specs[i][1], specs[i][2], specs[i][3]
        );
    end loop;
end $$;
