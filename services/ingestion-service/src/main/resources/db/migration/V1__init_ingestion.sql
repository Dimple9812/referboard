-- ingestion-service schema: where jobs come from, what has been seen, and each run's outcome.

-- One row per fetchable source: an ATS board (greenhouse:razorpay) or an aggregator query (adzuna:in:java developer).
create table sources (
    source_key           varchar(200) primary key,
    source_type          varchar(20)   not null check (source_type in ('GREENHOUSE', 'LEVER', 'ASHBY', 'ADZUNA', 'SAMPLE')),
    slug                 varchar(200)  not null,
    display_name         varchar(200)  not null,
    enabled              boolean       not null default true,
    status               varchar(20)   not null default 'NEW'
        check (status in ('NEW', 'OK', 'FAILING', 'NOT_FOUND', 'BLOCKED')),
    last_attempt_at      timestamptz,
    last_success_at      timestamptz,   -- a posting is only closed if its source was read successfully after it vanished
    last_error           varchar(1000),
    consecutive_failures int           not null default 0,
    last_job_count       int,
    created_at           timestamptz   not null default now(),
    updated_at           timestamptz   not null default now()
);

create table ingestion_runs (
    id             uuid primary key,
    trigger_type   varchar(20)  not null check (trigger_type in ('SCHEDULED', 'MANUAL', 'STARTUP')),
    started_at     timestamptz  not null,
    finished_at    timestamptz,
    status         varchar(20)  not null check (status in ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    sources_ok     int          not null default 0,
    sources_failed int          not null default 0,
    fetched        int          not null default 0,
    kept           int          not null default 0,   -- after India/remote + tech-role filters
    created        int          not null default 0,
    updated        int          not null default 0,
    unchanged      int          not null default 0,
    closed         int          not null default 0,
    errors         jsonb        not null default '[]'
);
create index ix_ingestion_runs_started on ingestion_runs (started_at desc);

-- Every distinct job ever seen, keyed by the de-dup key. job_id = UUID.nameUUIDFromBytes(dedup_key).
create table ingested_postings (
    job_id              uuid primary key,
    dedup_key           varchar(700) not null,
    source              varchar(20)  not null,
    source_key          varchar(200) not null,
    source_job_id       varchar(200) not null,
    company_name        varchar(200) not null,
    title               varchar(300) not null,
    location            varchar(300),
    content_hash        varchar(64)  not null,
    posted_at           timestamptz,
    first_seen_at       timestamptz  not null,
    last_seen_at        timestamptz  not null,
    closed_at           timestamptz,
    repost_count        int          not null default 0,
    ghost_job_suspected boolean      not null default false,
    embedding_model     varchar(100),
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);
create unique index ux_ingested_postings_dedup on ingested_postings (dedup_key);
create index ix_ingested_postings_open_last_seen on ingested_postings (last_seen_at) where closed_at is null;
create index ix_ingested_postings_source on ingested_postings (source_key);

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
