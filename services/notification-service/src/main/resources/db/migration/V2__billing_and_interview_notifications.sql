-- Payment confirmations (billing-service) and interview prep nudges (tracker card moved to Interview).
alter table notifications drop constraint if exists notifications_type_check;
alter table notifications add constraint notifications_type_check
    check (type in ('REFERRAL_REMINDER', 'DAILY_DIGEST', 'WELCOME', 'SYSTEM', 'BILLING', 'INTERVIEW'));
