-- notification-service schema: who to email (from UserRegistered), their preferences, in-app notifications and an
-- email queue. Emails are queued in the same transaction as the notification and sent by a background worker,
-- so an SMTP outage never blocks Kafka consumption.

create table recipients (
    user_id    uuid primary key,
    email      varchar(320) not null,
    full_name  varchar(200),
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

create table notification_preferences (
    user_id           uuid primary key,
    email_reminders   boolean     not null default true,
    email_digest      boolean     not null default true,
    unsubscribe_token uuid        not null unique,     -- one-click unsubscribe links in emails
    updated_at        timestamptz not null default now()
);

create table notifications (
    id         uuid primary key,
    user_id    uuid          not null,
    type       varchar(30)   not null check (type in ('REFERRAL_REMINDER', 'DAILY_DIGEST', 'WELCOME', 'SYSTEM')),
    title      varchar(300)  not null,
    body       varchar(4000) not null,
    link       varchar(500),                          -- app path, e.g. /referrals/contacts/{id}
    data       jsonb         not null default '{}',
    dedup_key  varchar(200)  not null,                -- reminder:{id}, digest:{date}: one notification each
    read_at    timestamptz,
    created_at timestamptz   not null default now(),
    deleted_at timestamptz
);
create unique index ux_notifications_dedup on notifications (user_id, dedup_key);
create index ix_notifications_user on notifications (user_id, created_at desc) where deleted_at is null;
create index ix_notifications_unread on notifications (user_id) where read_at is null and deleted_at is null;

create table email_deliveries (
    id              uuid primary key,
    user_id         uuid          not null,
    notification_id uuid references notifications (id) on delete set null,
    template        varchar(40)   not null,
    to_address      varchar(320)  not null,
    subject         varchar(300)  not null,
    html_body       text          not null,
    text_body       text          not null,
    unsubscribe_url varchar(1000),
    status          varchar(20)   not null default 'QUEUED'
        check (status in ('QUEUED', 'SENT', 'FAILED', 'SKIPPED')),
    skip_reason     varchar(100),
    attempts        int           not null default 0,
    next_attempt_at timestamptz   not null default now(),
    last_error      text,
    created_at      timestamptz   not null default now(),
    sent_at         timestamptz
);
create index ix_email_due on email_deliveries (next_attempt_at) where status = 'QUEUED';
create index ix_email_user_day on email_deliveries (user_id, created_at);

create table processed_events (
    event_id     uuid primary key,
    topic        varchar(200) not null,
    processed_at timestamptz  not null default now()
);
