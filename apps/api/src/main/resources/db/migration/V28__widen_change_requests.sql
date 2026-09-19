create type change_request_entity as enum ('item', 'procedure', 'procedure_bom', 'procedure_case');

alter table inventory_change_request
    add column entity change_request_entity not null default 'item',
    add column procedure_id uuid references procedure (id),
    add column procedure_case_id uuid references procedure_case (id);

alter table inventory_change_request
    add constraint inventory_change_request_target_ck check (
        (kind = 'create' and item_id is null and procedure_id is null and procedure_case_id is null and payload is not null)
        or (kind <> 'create' and entity = 'item' and item_id is not null and procedure_id is null and procedure_case_id is null)
        or (kind <> 'create' and entity = 'procedure' and item_id is null and procedure_id is not null and procedure_case_id is null)
        or (kind <> 'create' and entity = 'procedure_bom' and item_id is null and procedure_id is not null and procedure_case_id is null)
        or (kind <> 'create' and entity = 'procedure_case' and item_id is null and procedure_id is null and procedure_case_id is not null)
    );

create trigger trg_inventory_change_request_procedure_same_clinic
    before insert or update on inventory_change_request
    for each row
    execute function assert_same_clinic('procedure_id', 'procedure');

create trigger trg_inventory_change_request_case_same_clinic
    before insert or update on inventory_change_request
    for each row
    execute function assert_same_clinic('procedure_case_id', 'procedure_case');
