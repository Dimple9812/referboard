-- resume-analyzer-service schema: parsed-JD cache, analyses (the full report as JSON plus queryable columns),
-- tailored resume versions (structured content; files are rendered on download), and the monthly usage counter.

create table parsed_jds (
    content_hash   varchar(64) primary key,     -- sha256 of the normalised JD text + parser version
    parser         varchar(20)  not null check (parser in ('LLM', 'HEURISTIC')),
    model          varchar(100),
    prompt_version varchar(20)  not null,
    parsed         jsonb        not null,
    hit_count      int          not null default 0,
    created_at     timestamptz  not null default now(),
    last_used_at   timestamptz  not null default now()
);

create table jd_analyses (
    id              uuid primary key,
    user_id         uuid          not null,
    job_id          uuid,                                    -- set when analysed against a ReferBoard job
    job_title       varchar(300),
    company_name    varchar(200),
    jd_source       varchar(20)   not null check (jd_source in ('TEXT', 'UPLOAD', 'JOB')),
    jd_hash         varchar(64)   not null,
    jd_text         text          not null,
    resume_source   varchar(20)   not null check (resume_source in ('PROFILE_RESUME', 'VERSION')),
    resume_ref_id   uuid          not null,
    resume_name     varchar(300),
    score           smallint      not null check (score between 0 and 100),
    verdict         varchar(20)   not null check (verdict in ('STRONG', 'GOOD', 'PARTIAL', 'WEAK')),
    projected_score smallint      not null,
    report          jsonb         not null,                  -- breakdown, skills, ATS checks, suggestions
    resume_content  jsonb         not null,                  -- snapshot the suggestions apply to
    created_at      timestamptz   not null default now(),
    deleted_at      timestamptz
);
create index ix_analyses_user on jd_analyses (user_id, created_at desc) where deleted_at is null;
create index ix_analyses_user_job on jd_analyses (user_id, job_id) where deleted_at is null;
create index ix_analyses_reuse on jd_analyses (user_id, jd_hash, resume_ref_id, created_at desc) where deleted_at is null;

create table resume_versions (
    id                   uuid primary key,
    user_id              uuid          not null,
    name                 varchar(200)  not null,
    job_id               uuid,
    analysis_id          uuid references jd_analyses (id) on delete set null,
    base_source          varchar(20)   not null check (base_source in ('PROFILE_RESUME', 'VERSION')),
    base_ref_id          uuid          not null,
    content              jsonb         not null,             -- structured resume after accepted suggestions
    accepted_suggestions jsonb         not null default '[]',
    file_format          varchar(10)   not null check (file_format in ('DOCX', 'PDF')),
    score_before         smallint,
    score_after          smallint,
    created_at           timestamptz   not null default now(),
    deleted_at           timestamptz
);
create index ix_versions_user on resume_versions (user_id, created_at desc) where deleted_at is null;

create table usage_counters (
    user_id  uuid    not null,
    period   char(7) not null,                               -- YYYY-MM (UTC)
    analyses int     not null default 0,
    primary key (user_id, period)
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
