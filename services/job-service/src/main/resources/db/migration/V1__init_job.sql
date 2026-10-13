-- job-service schema: the jobs catalogue (fed by JobUpserted/JobClosed), a local copy of each user's matching
-- profile (fed by ProfileUpdated), and precomputed matches for recommendations and the daily digest.
-- Extensions vector and pg_trgm are created in schema public by the platform bootstrap (docker/postgres/init.sql).

create table companies (
    id         uuid primary key,
    slug       varchar(200) not null,   -- normalised company name; stable across sources
    name       varchar(200) not null,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);
create unique index ux_companies_slug on companies (slug);
create index ix_companies_name_trgm on companies using gin (name public.gin_trgm_ops);

create table jobs (
    id                  uuid primary key,          -- jobId from ingestion (deterministic from dedup_key)
    dedup_key           varchar(700) not null,
    source              varchar(20)  not null,
    source_job_id       varchar(200) not null,
    company_id          uuid         not null references companies (id),
    company_name        varchar(200) not null,
    title               varchar(300) not null,
    normalized_title    varchar(300) not null,
    location            varchar(300),
    country             varchar(2),
    remote              boolean      not null default false,
    employment_type     varchar(50),
    seniority           varchar(20),
    department          varchar(200),
    description_html    text,
    description_text    text,
    apply_url           varchar(2000) not null,
    salary_min          bigint,
    salary_max          bigint,
    salary_currency     varchar(3),
    posted_at           timestamptz,
    first_seen_at       timestamptz  not null,
    last_seen_at        timestamptz  not null,
    listed_at           timestamptz  not null,     -- coalesce(posted_at, first_seen_at): what "posted N days ago" means
    status              varchar(10)  not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
    closed_at           timestamptz,
    ghost_job_suspected boolean      not null default false,
    content_hash        varchar(64)  not null,
    embedding           public.vector(1536),
    embedding_model     varchar(100),
    search_tsv          tsvector generated always as (
        setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english'::regconfig, coalesce(company_name, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(left(description_text, 20000), '')), 'C')
    ) stored,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);
create unique index ux_jobs_dedup on jobs (dedup_key);
create index ix_jobs_status_listed on jobs (status, listed_at desc);
create index ix_jobs_status_remote_listed on jobs (status, remote, listed_at desc);
create index ix_jobs_company on jobs (company_id);
create index ix_jobs_location on jobs (lower(location) text_pattern_ops);
create index ix_jobs_search on jobs using gin (search_tsv);
create index ix_jobs_title_trgm on jobs using gin (title public.gin_trgm_ops);
create index ix_jobs_embedding on jobs using hnsw (embedding public.vector_cosine_ops);

create table job_skills (
    job_id   uuid         not null references jobs (id) on delete cascade,
    skill    varchar(100) not null,   -- canonical name from the shared skill taxonomy
    position smallint     not null,   -- order of first mention in the posting
    primary key (job_id, skill)
);
create index ix_job_skills_skill on job_skills (skill);

-- Local copy of each user's ProfileUpdated; job-service never reads the profile schema.
create table user_match_profiles (
    user_id          uuid primary key,
    profile_version  bigint        not null,
    headline         varchar(300),
    skills           jsonb         not null default '[]',
    experience_years numeric(4, 1),
    preferences      jsonb         not null default '{}',
    embedding        public.vector(1536),
    embedding_model  varchar(100),
    updated_at       timestamptz   not null default now()
);

-- Precomputed matches (recommendations, sort=match, daily digest). Search pages compute match % live.
create table job_matches (
    user_id          uuid        not null,
    job_id           uuid        not null references jobs (id) on delete cascade,
    score            smallint    not null check (score between 0 and 100),
    semantic_score   real,
    skill_score      real,
    preference_score real,
    matched_skills   jsonb       not null default '[]',
    missing_skills   jsonb       not null default '[]',
    computed_at      timestamptz not null,
    digested_at      timestamptz,
    primary key (user_id, job_id)
);
create index ix_job_matches_user_score on job_matches (user_id, score desc);
create index ix_job_matches_digest on job_matches (computed_at) where digested_at is null;

-- Jobs created/changed since the last match refresh (scored against all users in the background).
create table pending_match_jobs (
    job_id    uuid primary key references jobs (id) on delete cascade,
    queued_at timestamptz not null default now()
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
