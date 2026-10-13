-- profile-service schema: resumes, the structured candidate profile derived from them, and job preferences.

create table resumes (
    id             uuid primary key,
    user_id        uuid          not null,
    file_name      varchar(255)  not null,
    content_type   varchar(100)  not null,
    size_bytes     bigint        not null,
    sha256         varchar(64)   not null,
    storage_key    varchar(500)  not null,
    extracted_text text,
    parse_status   varchar(20)   not null default 'PENDING'
        check (parse_status in ('PENDING', 'PARSED', 'FAILED')),
    parse_error    varchar(1000),
    parser         varchar(20)   check (parser in ('LLM', 'HEURISTIC')),
    parser_model   varchar(100),
    parsed_json    jsonb,                      -- raw structured parse, kept for audit and the resume analyzer
    parsed_at      timestamptz,
    is_primary     boolean       not null default false,
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),
    deleted_at     timestamptz
);
create index ix_resumes_user on resumes (user_id, created_at desc) where deleted_at is null;
create unique index ux_resumes_one_primary on resumes (user_id) where is_primary and deleted_at is null;

create table profiles (
    user_id                uuid primary key,
    full_name              varchar(200),
    email                  varchar(320),
    phone                  varchar(40),
    headline               varchar(300),
    summary                text,
    current_title          varchar(200),
    current_company        varchar(200),
    location               varchar(200),
    total_experience_years numeric(4, 1),
    college                varchar(300),
    highest_degree         varchar(200),
    links                  jsonb         not null default '[]',
    certifications         jsonb         not null default '[]',
    parse_source           varchar(20),  -- LLM | HEURISTIC | MANUAL: how the current content was produced
    primary_resume_id      uuid,
    profile_version        bigint        not null default 0,
    embedding              public.vector(1536),
    embedding_model        varchar(100),
    embedding_version      bigint,       -- profile_version the embedding was computed for
    created_at             timestamptz   not null default now(),
    updated_at             timestamptz   not null default now(),
    version                bigint        not null default 0
);

create table profile_skills (
    id              uuid primary key,
    user_id         uuid          not null references profiles (user_id) on delete cascade,
    name            varchar(100)  not null,   -- canonical (normalized) name
    display_name    varchar(100)  not null,
    years           numeric(4, 1),
    source          varchar(20)   not null check (source in ('PARSED', 'USER')),
    evidence        varchar(20)   not null check (evidence in ('BULLET', 'SKILLS_LIST')),
    sort_order      int           not null default 0,
    created_at      timestamptz   not null default now()
);
create unique index ux_profile_skills_user_name on profile_skills (user_id, name);

create table experiences (
    id          uuid primary key,
    user_id     uuid          not null references profiles (user_id) on delete cascade,
    company     varchar(200)  not null,
    title       varchar(200)  not null,
    location    varchar(200),
    start_date  date,
    end_date    date,
    is_current  boolean       not null default false,
    description text,
    bullets     jsonb         not null default '[]',
    sort_order  int           not null default 0,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now()
);
create index ix_experiences_user on experiences (user_id, sort_order);

create table educations (
    id             uuid primary key,
    user_id        uuid          not null references profiles (user_id) on delete cascade,
    institution    varchar(300)  not null,
    degree         varchar(200),
    field_of_study varchar(200),
    start_year     int,
    end_year       int,
    grade          varchar(50),
    sort_order     int           not null default 0
);
create index ix_educations_user on educations (user_id, sort_order);

create table job_preferences (
    user_id          uuid primary key,
    roles            jsonb        not null default '[]',
    locations        jsonb        not null default '[]',
    remote_only      boolean      not null default false,
    open_to_remote   boolean      not null default true,
    min_salary_inr   bigint       check (min_salary_inr is null or min_salary_inr >= 0),
    experience_level varchar(20)
        check (experience_level in ('INTERN', 'ENTRY', 'MID', 'SENIOR', 'LEAD', 'PRINCIPAL')),
    updated_at       timestamptz  not null default now()
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
