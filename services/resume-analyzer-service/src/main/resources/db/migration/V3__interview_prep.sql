-- Interview prep plans (one per analysis) and the user's practice notes per question.
create table interview_preps (
    id           uuid primary key,
    user_id      uuid          not null,
    analysis_id  uuid references jd_analyses (id) on delete set null,
    job_id       uuid,
    job_title    varchar(300),
    company_name varchar(200),
    items        jsonb         not null,                    -- the plan: PrepItem[] in display order
    ai_questions boolean       not null default false,
    created_at   timestamptz   not null default now(),
    deleted_at   timestamptz
);
create unique index ux_preps_analysis on interview_preps (analysis_id) where deleted_at is null;
create index ix_preps_user on interview_preps (user_id, created_at desc) where deleted_at is null;
create index ix_preps_user_job on interview_preps (user_id, job_id) where deleted_at is null;

create table prep_answers (
    prep_id    uuid         not null references interview_preps (id) on delete cascade,
    item_id    varchar(20)  not null,
    notes      text,
    confidence varchar(10)  not null default 'NEW' check (confidence in ('NEW', 'SHAKY', 'READY')),
    updated_at timestamptz  not null default now(),
    primary key (prep_id, item_id)
);
