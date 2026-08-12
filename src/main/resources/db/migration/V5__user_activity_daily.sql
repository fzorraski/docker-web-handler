-- Daily roll-up of the audit trail into per-user activity totals. Reports read
-- this instead of scanning audit_log, and it outlives the raw entries: once
-- audit retention deletes them the counts here are the only surviving history.
CREATE TABLE user_activity_daily (
    activity_day  date   NOT NULL,
    actor         text   NOT NULL,
    action        text   NOT NULL,
    tenant_id     text,
    event_count   bigint NOT NULL
);

-- tenant_id is nullable (system and super-admin actions belong to no tenant),
-- and NULL columns cannot carry a primary key, so uniqueness goes through an
-- expression index. It doubles as the ON CONFLICT target of the roll-up upsert.
CREATE UNIQUE INDEX ux_user_activity_daily
    ON user_activity_daily (activity_day, actor, action, COALESCE(tenant_id, ''));

-- "what did this user do over this range", the shape the reports screen asks for
CREATE INDEX ix_user_activity_daily_actor_day ON user_activity_daily (actor, activity_day);

-- How far the summariser has got, as a single row (same shape as
-- runtime_settings). It cannot be derived from max(activity_day): a day with no
-- activity writes no rows, so the watermark would never move past it and the
-- job would rescan an ever-widening range forever.
CREATE TABLE activity_summary_state (
    id                 int PRIMARY KEY CHECK (id = 1),
    summarised_through date
);
