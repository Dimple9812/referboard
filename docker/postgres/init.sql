-- Runs once when the Postgres container initialises its data directory.
-- One database, one schema + one login role per service. Each role owns only its schema and has no grants
-- on the others, so "services never read each other's tables" is enforced by the database, not just by
-- convention. Passwords here are for local development only; override them in any shared environment.

create extension if not exists vector schema public;
create extension if not exists pg_trgm schema public;   -- fuzzy title search in job-service

do $$
declare
    svc text;
begin
    foreach svc in array array['auth', 'profile', 'job', 'ingestion', 'referral', 'analyzer', 'tracker',
                               'notification']
    loop
        if not exists (select 1 from pg_roles where rolname = svc || '_svc') then
            execute format('create role %I login password %L', svc || '_svc', svc || '_svc');
        end if;
        execute format('create schema if not exists %I authorization %I', svc, svc || '_svc');
        execute format('revoke all on schema %I from public', svc);
        -- pgvector types/operators live in public
        execute format('grant usage on schema public to %I', svc || '_svc');
    end loop;
end
$$;

revoke create on schema public from public;
