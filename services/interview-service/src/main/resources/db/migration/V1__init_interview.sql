-- Interview prep packs. A pack is built from one analysis (its parsed posting and the resume it compared) and
-- keeps a copy of the questions so it doesn't change if the analysis is deleted. Users add their own notes.
create table prep_packs (
    id           uuid primary key,
    user_id      uuid          not null,
    analysis_id  uuid          not null,
    job_id       uuid,
    job_title    varchar(300),
    company_name varchar(200),
    origin       varchar(10)   not null check (origin in ('RULES', 'AI')),   -- AI: rules plus AI questions
    model        varchar(100),
    created_at   timestamptz   not null default now(),
    deleted_at   timestamptz
);
create index ix_packs_user on prep_packs (user_id, created_at desc) where deleted_at is null;
create index ix_packs_user_job on prep_packs (user_id, job_id) where deleted_at is null;

create table prep_items (
    id            uuid primary key,
    pack_id       uuid          not null references prep_packs (id) on delete cascade,
    position      int           not null,
    section       varchar(20)   not null check (section in ('INTRO', 'TECHNICAL', 'ROLE', 'BEHAVIOURAL', 'GAPS', 'ASK')),
    question      varchar(500)  not null,
    checks        varchar(500),
    tip           varchar(800),
    evidence      varchar(1000),                       -- a resume bullet, verbatim
    evidence_from varchar(300),
    skill         varchar(100),
    origin        varchar(10)   not null check (origin in ('RULES', 'AI')),
    notes         text,                                -- the user's own answer notes
    confidence    varchar(20)   not null default 'NEW' check (confidence in ('NEW', 'PRACTISING', 'CONFIDENT')),
    updated_at    timestamptz,
    version       bigint        not null default 0,
    unique (pack_id, position)
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
