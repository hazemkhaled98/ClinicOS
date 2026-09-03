-- Cross-cutting: attachments (photo pointers, replacing inline image blobs like
-- acadimg:), activity log, notifications. Also wires the photo_id FKs that were
-- deferred until this table existed.

create table attachment (
    id              uuid primary key default gen_random_uuid(),
    clinic_id       uuid not null references clinic (id) on delete cascade,
    storage_key     text not null,
    content_type    text not null,
    byte_size       integer not null check (byte_size >= 0),
    uploaded_by     uuid references membership (id),
    uploaded_at     timestamptz not null default now()
);

create index idx_attachment_clinic on attachment (clinic_id);

alter table daily_task_completion
    add constraint fk_daily_task_completion_photo foreign key (photo_id) references attachment (id);

alter table task_assignment
    add constraint fk_task_assignment_proof_photo foreign key (proof_photo_id) references attachment (id),
    add constraint fk_task_assignment_verification_photo foreign key (verification_photo_id) references attachment (id);

alter table academy_step_submission
    add constraint fk_academy_submission_photo foreign key (photo_id) references attachment (id);

alter table purchase_order
    add constraint fk_purchase_order_invoice_photo foreign key (invoice_photo_id) references attachment (id);

create table activity_log (
    id                      uuid primary key default gen_random_uuid(),
    clinic_id               uuid not null references clinic (id) on delete cascade,
    actor_membership_id     uuid references membership (id),
    action                  text not null,
    entity_type             text not null,
    entity_id               uuid,
    detail                  jsonb,
    occurred_at             timestamptz not null default now()
);

create index idx_activity_log_clinic on activity_log (clinic_id, occurred_at desc);

create table notification (
    id                          uuid primary key default gen_random_uuid(),
    clinic_id                   uuid not null references clinic (id) on delete cascade,
    recipient_membership_id     uuid not null references membership (id) on delete cascade,
    kind                        text not null,
    payload                     jsonb,
    read_at                     timestamptz,
    created_at                  timestamptz not null default now()
);

create index idx_notification_recipient on notification (recipient_membership_id, read_at);
