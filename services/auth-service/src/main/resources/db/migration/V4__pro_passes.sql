-- Pro passes bought in billing-service. While pro_until is in the future, access tokens carry the PRO role.
-- pro_changed_at orders updates so a late, older event can't undo a newer one.
alter table users add column pro_until timestamptz;
alter table users add column pro_changed_at timestamptz;
