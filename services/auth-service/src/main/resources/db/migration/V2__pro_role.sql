-- PRO: paid plan (unlimited JD analyses). Granted manually until payments (Phase 3) set it.
alter table users drop constraint users_role_check;
alter table users add constraint users_role_check check (role in ('USER', 'PRO', 'ADMIN'));
