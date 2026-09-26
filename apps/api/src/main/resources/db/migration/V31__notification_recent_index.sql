create index idx_notification_recipient_recent
    on notification (recipient_membership_id, created_at desc);
