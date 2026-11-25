create index notifications_unread_inbox
    on notifications (recipient, created_at desc, id desc)
    where not read;
