-- Baseline schema for the application's own persistence store.
-- Replaces the JSON files under data/ (see docs/postgres-persistence-migration-plan.md).
-- Ids are text: they carry the UUID strings already present in the JSON files.

CREATE TABLE app_user (
    id              text PRIMARY KEY,
    username        text NOT NULL,
    password_hash   text,
    role_ids        jsonb NOT NULL DEFAULT '[]',
    tenant_ids      jsonb NOT NULL DEFAULT '[]',
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz,
    updated_at      timestamptz,
    last_login_at   timestamptz
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));

CREATE TABLE tenant (
    id                    text PRIMARY KEY,
    name                  text NOT NULL,
    description           text,
    enabled_repositories  jsonb,
    enabled_databases     jsonb,
    created_at            timestamptz,
    updated_at            timestamptz
);
CREATE UNIQUE INDEX ux_tenant_name ON tenant (lower(name));

CREATE TABLE role (
    id           text PRIMARY KEY,
    name         text NOT NULL,
    description  text,
    permissions  jsonb NOT NULL DEFAULT '[]',
    built_in     boolean NOT NULL DEFAULT false,
    created_at   timestamptz
);
CREATE UNIQUE INDEX ux_role_name ON role (lower(name));

CREATE TABLE container_schedule (
    id                      text PRIMARY KEY,
    name                    text,
    action                  text,
    schedule_type           text,
    enabled                 boolean NOT NULL DEFAULT true,
    cron_expression         text,
    scheduled_at            timestamptz,
    container_id            text,
    container_name          text,
    create_config           jsonb,
    next_execution_at       timestamptz,
    last_executed_at        timestamptz,
    last_execution_status   text,
    last_execution_message  text,
    created_by              text,
    tenant_id               text,
    created_at              timestamptz
);
CREATE INDEX ix_container_schedule_enabled ON container_schedule (enabled);

CREATE TABLE managed_database (
    repository          text NOT NULL,
    name                text NOT NULL,
    protected_flag      boolean NOT NULL DEFAULT false,
    app_last_used_at    timestamptz,
    created_at          timestamptz,
    description         text,
    last_restored_from  text,
    last_restored_at    timestamptz,
    created_by          text,
    tenant_id           text,
    PRIMARY KEY (repository, name)
);
-- The current JSON repo matches (repository, name) case-insensitively;
-- this index is the real uniqueness guard.
CREATE UNIQUE INDEX ux_managed_database_ci ON managed_database (lower(repository), lower(name));

CREATE TABLE database_dump (
    id                   text PRIMARY KEY,
    original_filename    text NOT NULL,
    stored_filename      text NOT NULL,
    database_name        text,
    version              text,
    md5_hash             text,
    uploaded_at          timestamptz,
    expires_at           timestamptz,
    file_size            bigint NOT NULL DEFAULT 0,
    format               text,
    description          text,
    last_used_at         timestamptz,
    created_by           text,
    tenant_id            text,
    shared_with_tenants  jsonb
);
CREATE UNIQUE INDEX ux_database_dump_md5 ON database_dump (md5_hash);
CREATE UNIQUE INDEX ux_database_dump_filename ON database_dump (original_filename);

CREATE TABLE database_snapshot (
    id                    text PRIMARY KEY,
    stored_filename       text NOT NULL,
    repository            text,
    source_database_name  text,
    format                text,
    md5_hash              text,
    created_at            timestamptz,
    expires_at            timestamptz,
    file_size             bigint NOT NULL DEFAULT 0,
    label                 text,
    container_name        text,
    description           text,
    last_used_at          timestamptz,
    temporary             boolean NOT NULL DEFAULT false,
    created_by            text,
    tenant_id             text,
    shared_with_tenants   jsonb
);
CREATE INDEX ix_database_snapshot_expires ON database_snapshot (expires_at);

CREATE TABLE container_expiration (
    short_id                        text PRIMARY KEY,
    full_container_id               text,
    expires_at                      timestamptz,
    repository                      text,
    database_name                   text,
    delete_database_on_expiration   boolean NOT NULL DEFAULT false
);
CREATE INDEX ix_container_expiration_expires ON container_expiration (expires_at);

CREATE TABLE database_migration (
    database_name      text NOT NULL,
    repository         text NOT NULL,
    source_version     text,
    target_version     text,
    versions_included  jsonb,
    total_statements   integer,
    mode               text,
    migrated_at        timestamptz,
    PRIMARY KEY (database_name, repository)
);

-- Single-row table for runtime setting overrides (null column = no override).
CREATE TABLE runtime_settings (
    id                             integer PRIMARY KEY CHECK (id = 1),
    terminal_enabled               boolean,
    terminal_max_sessions          integer,
    terminal_idle_timeout_minutes  integer,
    terminal_upload_enabled        boolean,
    terminal_upload_max_size_mb    integer,
    log_analyzer_enabled           boolean,
    session_timeout_minutes        integer,
    audit_retention_days           integer,
    updated_at                     timestamptz
);

CREATE TABLE audit_log (
    id           bigserial PRIMARY KEY,
    occurred_at  timestamptz NOT NULL,
    actor        text,
    action       text NOT NULL,
    target       text,
    detail       text
);
CREATE INDEX ix_audit_log_occurred ON audit_log (occurred_at);

CREATE TABLE auth_session (
    token_hash        text PRIMARY KEY,
    user_id           text,
    created_at        timestamptz NOT NULL,
    last_accessed_at  timestamptz NOT NULL
);
CREATE INDEX ix_auth_session_last_accessed ON auth_session (last_accessed_at);
CREATE INDEX ix_auth_session_user ON auth_session (user_id);

CREATE TABLE resource_counter (
    counter_key    text PRIMARY KEY,
    counter_value  bigint NOT NULL DEFAULT 0
);

CREATE TABLE image_usage (
    image_id      text PRIMARY KEY,
    last_used_at  timestamptz NOT NULL
);

-- One row per store imported from the legacy JSON files; prevents re-import.
CREATE TABLE json_import_history (
    store             text PRIMARY KEY,
    source_file       text,
    records_imported  integer NOT NULL DEFAULT 0,
    imported_at       timestamptz NOT NULL DEFAULT now()
);
