-- A read model built only from Kafka events (no other service's tables). Times are UTC; `day` is the calendar day in
-- India (Asia/Kolkata), which is how weeks are reported.

-- One row per user: when they joined and their Pro pass.
create table user_facts (
    user_id           uuid primary key,
    registered_at     timestamptz,
    pro_until         timestamptz,
    pro_changed_at    timestamptz,
    first_purchase_at timestamptz
);

-- Each tracker card: its current stage and when it first reached each stage (order of arrival doesn't matter).
create table applications (
    application_id        uuid primary key,
    user_id               uuid         not null,
    job_id                uuid,
    stage                 varchar(30)  not null,
    stage_changed_at      timestamptz  not null,
    created_at            timestamptz  not null,
    applied_at            timestamptz,
    referral_requested_at timestamptz,
    referred_at           timestamptz,
    interview_at          timestamptz,
    offer_at              timestamptz,
    rejected_at           timestamptz
);
create index ix_apps_user on applications (user_id);

-- Each referral contact: current status and when it first reached each status.
create table contacts (
    contact_id        uuid primary key,
    user_id           uuid        not null,
    job_id            uuid,
    status            varchar(30) not null,
    status_changed_at timestamptz not null,
    created_at        timestamptz not null,
    request_sent_at   timestamptz,
    connected_at      timestamptz,
    asked_at          timestamptz,
    referred_at       timestamptz,
    no_response_at    timestamptz
);
create index ix_contacts_user on contacts (user_id);

-- Things that happened, one row per event, for weekly activity and admin metrics.
create table activity (
    event_id     uuid primary key,
    user_id      uuid         not null,
    kind         varchar(30)  not null,
    occurred_at  timestamptz  not null,
    day          date         not null,
    score        int,                      -- ANALYSIS: match score; TAILORED_RESUME: score after
    delta        int,                      -- TAILORED_RESUME: points gained
    amount_paise bigint,                   -- PURCHASE (positive) and REFUND (negative)
    ref_id       uuid
);
create index ix_activity_user_day on activity (user_id, day);
create index ix_activity_day_kind on activity (day, kind);

create table processed_events (
    event_id     uuid primary key,
    topic        varchar(200) not null,
    processed_at timestamptz  not null default now()
);
