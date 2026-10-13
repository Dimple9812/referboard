-- referral-service schema: the referral CRM (contacts per job and their pipeline), drafted messages, reminders,
-- and snapshots of the job and user facts needed to draft messages when no user request is in flight.

create table job_snapshots (
    job_id              uuid primary key,
    title               varchar(300)  not null,
    company_name        varchar(200)  not null,
    location            varchar(300),
    apply_url           varchar(2000),
    skills              jsonb         not null default '[]',
    description_excerpt text,
    fetched_at          timestamptz   not null
);

create table user_snapshots (
    user_id          uuid primary key,
    full_name        varchar(200),
    headline         varchar(300),
    current_title    varchar(200),
    current_company  varchar(200),
    experience_years numeric(4, 1),
    college          varchar(300),
    past_companies   jsonb        not null default '[]',
    skills           jsonb        not null default '[]',
    achievements     jsonb        not null default '[]',   -- resume bullets with numbers, verbatim
    fetched_at       timestamptz  not null
);

create table contacts (
    id                uuid primary key,
    user_id           uuid         not null,
    job_id            uuid         not null references job_snapshots (job_id),
    full_name         varchar(200) not null,
    profile_url       varchar(500),
    headline          varchar(300),
    person_type       varchar(30)  not null
        check (person_type in ('RECRUITER', 'ENGINEERING_MANAGER', 'TEAM_MEMBER', 'ALUMNI', 'EX_COLLEAGUE', 'OTHER')),
    status            varchar(20)  not null default 'NOT_CONTACTED'
        check (status in ('NOT_CONTACTED', 'REQUEST_SENT', 'CONNECTED', 'REFERRAL_ASKED', 'REFERRED', 'NO_RESPONSE')),
    request_sent_at   timestamptz,
    connected_at      timestamptz,
    referral_asked_at timestamptz,
    referred_at       timestamptz,
    no_response_at    timestamptz,
    notes             varchar(2000),
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    deleted_at        timestamptz,
    version           bigint       not null default 0
);
create index ix_contacts_user_job on contacts (user_id, job_id) where deleted_at is null;
create index ix_contacts_user_status on contacts (user_id, status) where deleted_at is null;

create table contact_status_history (
    id          uuid primary key,
    contact_id  uuid        not null references contacts (id) on delete cascade,
    from_status varchar(20),
    to_status   varchar(20) not null,
    changed_at  timestamptz not null
);
create index ix_contact_history_contact on contact_status_history (contact_id, changed_at);

create table message_drafts (
    id             uuid primary key,
    user_id        uuid          not null,
    contact_id     uuid          not null references contacts (id) on delete cascade,
    kind           varchar(30)   not null check (kind in ('CONNECTION_NOTE', 'REFERRAL_REQUEST', 'FOLLOW_UP')),
    tone           varchar(20)   not null check (tone in ('WARM', 'CONCISE')),
    content        text          not null,
    char_count     int           not null,
    model          varchar(100)  not null,
    prompt_version varchar(20)   not null,
    copied_at      timestamptz,
    created_at     timestamptz   not null default now(),
    check (kind <> 'CONNECTION_NOTE' or char_count <= 300)
);
create index ix_message_drafts_contact on message_drafts (contact_id, created_at desc);

create table reminders (
    id                uuid primary key,
    user_id           uuid         not null,
    contact_id        uuid         not null references contacts (id) on delete cascade,
    type              varchar(30)  not null check (type in ('SEND_REFERRAL_MESSAGE', 'FOLLOW_UP')),
    trigger_status    varchar(20)  not null,   -- only fires if the contact is still in this status
    due_at            timestamptz  not null,
    status            varchar(20)  not null default 'PENDING'
        check (status in ('PENDING', 'FIRED', 'DONE', 'DISMISSED', 'OBSOLETE')),
    message           varchar(500),
    drafted_follow_up text,
    created_at        timestamptz  not null default now(),
    fired_at          timestamptz
);
create index ix_reminders_due on reminders (due_at) where status = 'PENDING';
create index ix_reminders_user on reminders (user_id, status);

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
