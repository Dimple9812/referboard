-- Which kind of client a token family belongs to. WEB families live in the httpOnly cookie; EXTENSION families are
-- held by the browser extension and refreshed with the token in the request body. A token only works on its own
-- client's refresh endpoint, so a leaked extension token can't be replayed as a web session cookie and vice versa.
alter table refresh_tokens
    add column client varchar(20) not null default 'WEB' check (client in ('WEB', 'EXTENSION'));
create index ix_refresh_tokens_user_client on refresh_tokens (user_id, client) where revoked_at is null;
