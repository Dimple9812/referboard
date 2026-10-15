-- Cover letters written from an analysis (its resume and parsed posting). The body is what the user last saved;
-- generated_body keeps the original draft so "edited" can be shown and the draft restored.
create table cover_letters (
    id             uuid primary key,
    user_id        uuid          not null,
    analysis_id    uuid references jd_analyses (id) on delete set null,
    job_id         uuid,
    job_title      varchar(300),
    company_name   varchar(200),
    length         varchar(10)   not null check (length in ('STANDARD', 'SHORT')),
    origin         varchar(10)   not null check (origin in ('TEMPLATE', 'AI')),
    model          varchar(100),
    motivation     varchar(600),                              -- the user's own "why this company", if given
    sender_name    varchar(200),
    sender_contact varchar(400),
    generated_body text          not null,
    body           text          not null,
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),
    deleted_at     timestamptz,
    version        bigint        not null default 0
);
create index ix_letters_user on cover_letters (user_id, created_at desc) where deleted_at is null;
create index ix_letters_user_job on cover_letters (user_id, job_id) where deleted_at is null;
