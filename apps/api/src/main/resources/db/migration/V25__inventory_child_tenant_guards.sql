-- Closes the follow-up V10's cross-tenant FK guard explicitly left open (see
-- the block comment above assert_same_clinic in V10__triggers.sql): a few
-- child tables carry no clinic_id column of their own, so they're scoped only
-- by a single-parent EXISTS in V9's RLS policy -- their own foreign keys are
-- not tenancy-guarded. purchase_order_line.item_id, procedure_bom.item_id and
-- procedure_case_item.item_id each reference inventory_item (which does carry
-- clinic_id) while the child's own clinic is only reachable through its
-- parent row; nothing previously stopped a cross-tenant reference on those
-- FKs. The generic helper here compares the referenced row's clinic_id against
-- the child's parent-derived clinic_id, mirroring the direct-table shape V10
-- covers.
create or replace function assert_same_clinic_via_parent()
returns trigger as $$
declare
    fk_column        text := tg_argv[0];
    ref_table        text := tg_argv[1];
    parent_fk_column text := tg_argv[2];
    parent_table     text := tg_argv[3];
    fk_value         uuid;
    parent_value     uuid;
    my_clinic        uuid;
    ref_clinic       uuid;
begin
    execute format('select ($1).%I', fk_column) into fk_value using new;
    if fk_value is null then
        return new;
    end if;

    execute format('select ($1).%I', parent_fk_column) into parent_value using new;
    if parent_value is null then
        return new;
    end if;

    -- `not found` isn't reliable after a dynamic EXECUTE ... INTO, so a
    -- missing row is detected the other way: both lookups here are into
    -- tables whose clinic_id is declared not null, so the variable staying
    -- NULL only happens when the row itself doesn't exist -- same rationale
    -- V10 documents in assert_same_clinic.
    --
    -- The referenced and parent tables are themselves RLS-protected, so this
    -- trigger (running as app_rw) can never actually SEE a different
    -- clinic's rows -- they come back as zero rows, identical to a missing
    -- id. The "not found" branch below therefore also swallows the
    -- cross-tenant case for a normal session, and the explicit mismatch
    -- branch stays as defense-in-depth only, exactly as V10's does.
    execute format('select clinic_id from %I where id = $1', parent_table) into my_clinic using parent_value;
    if my_clinic is null then
        raise exception '% % referenced by %.% not found (or belongs to a different clinic, which looks identical under RLS)', parent_table, parent_value, tg_table_name, parent_fk_column;
    end if;

    execute format('select clinic_id from %I where id = $1', ref_table) into ref_clinic using fk_value;
    if ref_clinic is null then
        raise exception '% % referenced by %.% not found (or belongs to a different clinic, which looks identical under RLS)', ref_table, fk_value, tg_table_name, fk_column;
    end if;

    if ref_clinic is distinct from my_clinic then
        raise exception 'cross-tenant reference: %.% (%) points to a % row in a different clinic', tg_table_name, fk_column, fk_value, ref_table;
    end if;

    return new;
end;
$$ language plpgsql;

do $$
declare
    specs text[][] := array[
        ['purchase_order_line', 'item_id', 'inventory_item', 'order_id', 'purchase_order'],
        ['procedure_bom', 'item_id', 'inventory_item', 'procedure_id', 'procedure'],
        ['procedure_case_item', 'item_id', 'inventory_item', 'procedure_case_id', 'procedure_case']
    ];
begin
    for i in 1 .. array_upper(specs, 1) loop
        execute format(
            'create trigger trg_%s_%s_same_clinic_via_parent before insert or update on %I for each row execute function assert_same_clinic_via_parent(%L, %L, %L, %L)',
            specs[i][1], specs[i][2], specs[i][1], specs[i][2], specs[i][3], specs[i][4], specs[i][5]
        );
    end loop;
end $$;

-- supplier_return_line.purchase_order_line_id is a three-hop case (the child
-- has no clinic_id, its parent supplier_return carries it, and the referenced
-- purchase_order_line is itself a two-hop child whose clinic only lives on
-- purchase_order) -- narrower than the three-column helper above, so it gets
-- its own bespoke trigger rather than generalized parameterization for a
-- single caller. Both hops must agree on the same clinic, or the reference is
-- cross-tenant.
create or replace function supplier_return_line_check_po_line_clinic()
returns trigger as $$
declare
    parent_clinic uuid;
    po_line_clinic uuid;
begin
    if new.purchase_order_line_id is null then
        return new;
    end if;

    select clinic_id into parent_clinic
    from supplier_return
    where id = new.supplier_return_id;

    if parent_clinic is null then
        raise exception 'supplier_return % not found (or belongs to a different clinic, which looks identical under RLS)', new.supplier_return_id;
    end if;

    select po.clinic_id into po_line_clinic
    from purchase_order_line pol
    join purchase_order po on po.id = pol.order_id
    where pol.id = new.purchase_order_line_id;

    if po_line_clinic is null then
        raise exception 'purchase_order_line % not found (or belongs to a different clinic, which looks identical under RLS)', new.purchase_order_line_id;
    end if;

    if po_line_clinic is distinct from parent_clinic then
        raise exception 'cross-tenant reference: supplier_return_line.purchase_order_line_id (%) points to a purchase_order_line row in a different clinic', new.purchase_order_line_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger trg_supplier_return_line_po_line_clinic
    before insert or update on supplier_return_line
    for each row
    execute function supplier_return_line_check_po_line_clinic();