-- auth-service schema. Owns identities and refresh tokens; nothing else in the platform reads these tables.

create table users (
    id                    uuid primary key,
    email                 varchar(320) not null,
    password_hash         varchar(100),               -- null for Google-only accounts
    full_name             varchar(200) not null,
    avatar_url            varchar(1000),
    auth_provider         varchar(20)  not null check (auth_provider in ('LOCAL', 'GOOGLE')),
    google_subject        varchar(255),
    email_verified        boolean      not null default false,
    role                  varchar(20)  not null default 'USER' check (role in ('USER', 'ADMIN')),
    status                varchar(20)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    failed_login_attempts int          not null default 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    deleted_at            timestamptz,
    version               bigint       not null default 0
);

-- Case-insensitive uniqueness among live accounts; a soft-deleted email can sign up again.
create unique index ux_users_email_live on users (lower(email)) where deleted_at is null;
create unique index ux_users_google_subject on users (google_subject) where google_subject is not null;

create table refresh_tokens (
    id          uuid primary key,
    user_id     uuid         not null references users (id) on delete cascade,
    token_hash  varchar(64)  not null,                -- SHA-256 hex; raw token is never stored
    family_id   uuid         not null,                -- all rotations of one login share a family
    expires_at  timestamptz  not null,
    revoked_at  timestamptz,
    replaced_by uuid,
    user_agent  varchar(500),
    ip_address  varchar(64),
    created_at  timestamptz  not null default now()
);

create unique index ux_refresh_tokens_hash on refresh_tokens (token_hash);
create index ix_refresh_tokens_user on refresh_tokens (user_id);
create index ix_refresh_tokens_family on refresh_tokens (family_id);
create index ix_refresh_tokens_expired on refresh_tokens (expires_at);

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
