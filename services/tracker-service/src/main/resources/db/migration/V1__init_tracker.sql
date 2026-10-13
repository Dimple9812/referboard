-- tracker-service schema: the user's application board. One card per (user, job); external jobs (applied outside
-- ReferBoard) have no job_id. Cards are ordered within a column by a fractional position.

create table applications (
    id                uuid primary key,
    user_id           uuid          not null,
    job_id            uuid,                                   -- null for jobs added by hand
    job_title         varchar(300)  not null,
    company_name      varchar(200)  not null,
    location          varchar(300),
    apply_url         varchar(2000),
    stage             varchar(30)   not null
        check (stage in ('SAVED', 'APPLIED', 'REFERRAL_REQUESTED', 'REFERRED', 'INTERVIEW', 'OFFER', 'REJECTED')),
    position          double precision not null,
    source            varchar(20)   not null default 'MANUAL' check (source in ('MANUAL', 'REFERRAL')),
    resume_version_id uuid,
    referral_summary  varchar(300),                           -- latest referral update, e.g. "Referred by Rohan Mehta"
    notes             text,
    applied_at        timestamptz,
    stage_changed_at  timestamptz   not null,
    next_action       varchar(200),                           -- e.g. "Technical interview round 2"
    next_action_at    timestamptz,
    job_closed_at     timestamptz,                            -- the posting closed (job-service JobClosed)
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    deleted_at        timestamptz,
    version           bigint        not null default 0
);
create unique index ux_applications_user_job on applications (user_id, job_id)
    where deleted_at is null and job_id is not null;
create index ix_applications_board on applications (user_id, stage, position) where deleted_at is null;
create index ix_applications_job on applications (job_id) where deleted_at is null;

create table application_events (
    id             uuid primary key,
    seq            bigint generated always as identity,   -- insertion order; entries in one transaction share a time
    application_id uuid        not null references applications (id) on delete cascade,
    type           varchar(30) not null
        check (type in ('CREATED', 'STAGE_CHANGED', 'NOTE', 'RESUME_LINKED', 'REFERRAL_UPDATE', 'JOB_CLOSED', 'UPDATED')),
    from_stage     varchar(30),
    to_stage       varchar(30),
    message        varchar(2000),
    details        jsonb       not null default '{}',
    occurred_at    timestamptz not null
);
create index ix_application_events_app on application_events (application_id, seq);

-- ResumeVersionCreated can arrive before the card for that job exists; applied when the card is created.
create table resume_links_pending (
    user_id           uuid        not null,
    job_id            uuid        not null,
    resume_version_id uuid        not null,
    version_name      varchar(200),
    created_at        timestamptz not null default now(),
    primary key (user_id, job_id)
);

-- Jobs closed before a card was created for them (rare, but keeps the "posting closed" badge correct).
create table closed_jobs (
    job_id    uuid primary key,
    closed_at timestamptz not null
);

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
