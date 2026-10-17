-- Pro passes bought with Razorpay. One row per Razorpay order; a paid order adds its plan's days to the user's
-- entitlement. Amounts are in paise. No card or UPI details are ever stored: Razorpay holds them.
create table orders (
    id                  uuid primary key,
    user_id             uuid         not null,
    plan                varchar(30)  not null,
    amount_paise        bigint       not null check (amount_paise > 0),
    currency            varchar(3)   not null default 'INR',
    days                int          not null check (days > 0),
    receipt             varchar(40)  not null unique,           -- our reference, shown on Razorpay too
    razorpay_order_id   varchar(64)  not null unique,
    status              varchar(20)  not null check (status in ('CREATED', 'PAID', 'REFUNDED', 'EXPIRED')),
    razorpay_payment_id varchar(64) unique,
    method              varchar(30),                            -- upi, card, netbanking… as reported by Razorpay
    pro_from            timestamptz,                            -- the period this order paid for
    pro_until           timestamptz,
    created_at          timestamptz  not null default now(),
    paid_at             timestamptz,
    refunded_at         timestamptz,
    version             bigint       not null default 0
);
create index ix_orders_user on orders (user_id, created_at desc);
create index ix_orders_open on orders (created_at) where status = 'CREATED';

-- Current Pro access per user: the end of the latest paid period.
create table entitlements (
    user_id    uuid primary key,
    pro_until  timestamptz not null,
    updated_at timestamptz not null default now()
);

-- Razorpay webhook deliveries, by their event id: each is applied once, and kept for audit.
create table webhook_events (
    event_id    varchar(100) primary key,
    event_type  varchar(60)  not null,
    received_at timestamptz  not null default now(),
    payload     jsonb        not null
);

-- Transactional outbox + idempotent consumer tables (same shape in every service; see common-lib).
create table outbox_events (
    id           uuid primary key,
    topic        varchar(200) not null,
    message_key  varchar(200) not null,
    event_type   varchar(100) not null,
    payload      jsonb        not null,
    created_at   timestamptz  not null default now(),
    published_at timestamptz,
    attempts     int          not null default 0,
    last_error   text
);
create index ix_outbox_unpublished on outbox_events (created_at) where published_at is null;

create table processed_events (
    event_id     uuid primary key,
    topic        varchar(200) not null,
    processed_at timestamptz  not null default now()
);
