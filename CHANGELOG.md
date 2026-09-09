# Changelog

## Unreleased

### Terminal image attachments

Paste or drop an image into the container terminal and its path inside the
container is typed into the prompt, ready for an LLM or any tool running there.
Docs: [docs/container-terminal.md](docs/container-terminal.md#image-attachments)

**Added**

- `POST /api/containers/{id}/upload/image`: validates the format by magic bytes
  before touching disk, generates a collision-safe name, and copies the file
  through Docker's archive API as a single-entry tar; missing directories under
  the administrator-configured image path are created by the Engine during
  extraction, so no shell command runs inside the container.
- Runtime settings, editable in the admin Settings tab: image attachments
  enabled (`container.terminal.upload.image.enabled`), image upload path
  (`container.terminal.upload.image.path`, default `/tmp`), and the path
  template (`container.terminal.upload.image.path-template`, default `{path}`;
  `"{path}"` quotes the path, `none` or an empty override types nothing).
- Ctrl+V and ⌘V hand the native paste to the browser while the feature is on;
  held-down chords do not repeat; text wins over an embedded preview image
  unless the text is only the pasted file's own name or `file://` URI; files
  without a MIME type are sniffed by the server.
- Audit entries `TERMINAL_UPLOAD` and `TERMINAL_IMAGE_UPLOAD` with user,
  container, file name, size, and destination.

**Changed**

- The generic file upload never creates directories (its destination is chosen
  by the requesting user); the image path is created on demand.
- Password checks in both upload endpoints run outside the catch-all, so the
  password rate limiter surfaces as `429` instead of a generic `500`.
- Closing the terminal aborts an in-flight image upload; runtime terminal
  settings are refreshed whenever a terminal opens or the tab regains focus.
- One shared XHR uploader serves image, dump, and log-analyzer uploads, so all
  three handle session expiry the same way.

**Migration notes**

- Flyway `V10__terminal_image_upload.sql`, `V11__terminal_image_upload_path.sql`,
  `V12__terminal_image_path_template.sql` add three nullable columns to
  `runtime_settings`.

### Grouped admin Settings tab with dependencies

The Settings tab groups runtime settings by category and nests each setting
under the flag that gates it. A child whose parent is off is dimmed with a note
naming the parent but stays editable. Audit retention shows a note while
`audit.enabled=false`, a restart-only property.
Docs: [docs/configuration.md](docs/configuration.md#admin-settings-tab)

**Added**

- `GET /api/settings` rows carry `category`, `dependsOn`, and
  `disabledByProperty`, so the UI never hard-codes the tree.
- String settings render as a text field with inline validation (container
  paths, path templates).

### Shared database metadata across repositories on one PostgreSQL server

Repositories whose `pg-host` and `pg-port` match list the same physical
databases and now share one metadata record per database: protection,
creator, tenant, description, restore stamp, and app-level last-used time
are the same in every tab, and a database protected in one tab cannot be
dropped from a sibling tab.
Docs: [docs/managed-databases.md](docs/managed-databases.md#repositories-sharing-a-postgresql-server)

**Added**

- `RepositorySiblingResolver` derives the grouping from configuration; a
  sibling-aware repository decorator resolves identity across siblings for
  every lookup and write. No schema change.
- One-time startup merge of records that forked before this release: the
  record that knows its creator wins, protection is OR-ed, the latest usage
  and earliest creation time are kept; winners are written before losers are
  removed, and the summary is logged. Kill switch
  `database.managed.sibling-merge-at-startup` (default `true`).

**Fixed**

- The listing and bulk delete match stored names case-insensitively, so a
  stored `MyDB` no longer gets a blank duplicate record for PostgreSQL's
  `mydb`.

**Migration notes**

- Back up the `managed_database` table (or `data/managed-databases.json`)
  before the first start: the merge deletes the duplicate rows it folds.
- Repositories that leave `allowed.run.repositories` but keep their `pg-host`
  still share; removing the `pg-host` too, or repointing it, orphans the rows
  they hold, so clear or re-home them first.

### PostgreSQL persistence (replaces JSON-file storage)

The application's own state now lives in a dedicated PostgreSQL database
instead of JSON files under `data/`. Setup: [docs/postgres-setup.md](docs/postgres-setup.md) ·
Architecture: [docs/persistence.md](docs/persistence.md) ·
Design rationale: [docs/postgres-persistence-migration-plan.md](docs/postgres-persistence-migration-plan.md)

**Added**

- Dedicated application datasource (`APP_DB_HOST`, `APP_DB_PORT`, `APP_DB_NAME`,
  `APP_DB_USER`, `APP_DB_PASSWORD`) with an Agroal connection pool; schema
  created and versioned by Flyway (`db/migration/V1__baseline.sql`, 15 tables).
- PostgreSQL repositories for every store: users, tenants, roles, container
  schedules, managed-database metadata, dump metadata, snapshot metadata,
  container expirations, database migration records, runtime settings, audit
  trail, resource counters, and image usage tracking.
- **Durable login sessions** (`auth_session` table): restarting the
  application no longer logs everyone out. Session tokens are stored as
  SHA-256 hashes; reads are cached (~30 s) and last-access writes throttled
  (`app.auth.session.touch-interval-seconds`, default 60).
- **One-time automatic import** of legacy `data/*.json` files on first boot
  (idempotent via a `json_import_history` marker table; imported files are
  renamed to `*.imported`; a failed store rolls back and aborts startup).
- Runtime backend switch `PERSISTENCE_BACKEND=postgres|file`. The file
  backend remains as a fallback for one release; `APP_DB_ACTIVE=false`
  deactivates the datasource and Flyway together so it boots without any
  PostgreSQL.
- Quarkus Dev Services: dev mode and tests start a disposable PostgreSQL
  automatically; new `@QuarkusTest` integration suites cover every repository,
  the importer, and concurrency races.
- `docker-compose.example.yml` with the `app-db` sidecar and healthcheck.

**Concurrency fixes** (the app serves 100+ simultaneous users)

- Duplicate user/tenant/role names and dump dedup (md5/filename) are now
  enforced by unique indexes — the previous check-then-act races are closed
  at the database (`lower(...)` indexes keep the case-insensitive semantics).
- The atomic `update(id, mutator)` repository contract is implemented as
  `SELECT … FOR UPDATE` transactions — safe across processes, not just
  within one JVM's lock.
- Role edits gained the atomic mutator; schedule execution results are
  written with a targeted `UPDATE` so a finishing run can no longer clobber a
  concurrent admin edit of the schedule definition.
- Resource counters increment atomically (`UPDATE … + 1`) instead of
  rewriting a whole JSON file per increment; the database-activity poller
  updates usage timestamps in one transaction (never regressing them)
  instead of rewriting `managed-databases.json` every 60 seconds.
- Per-authorization-check tenant reads now go through the cached
  `AuthorizationService` snapshot instead of re-reading the tenant store.
- Audit logging is a single `INSERT` (still never fails the user's action);
  retention is a SQL `DELETE`.

**Migration notes**

- Dump/snapshot binaries stay on disk (`data/dumps/`, `data/snapshots/`);
  only metadata moved. Keep the data volume mounted.
- `data/audit.log` history is not imported; new audit entries go to the
  `audit_log` table.
- Backups: `pg_dump` of the app database replaces copying `data/*.json`.

### Per-tenant repository & database entitlements (RBAC)

- Tenants can now be restricted to a subset of the globally configured
  repositories and database connections (edited in the tenant dialog,
  Admin → Tenants). Unrestricted tenants keep full access (backward
  compatible); users in several tenants get the union.
- Enforcement is server-side at every surface: run-container validation,
  repository/tag listings, Database Manager tabs and all per-repository
  operations, dumps, snapshots, and migrations. Non-entitled repositories
  return 404, indistinguishable from nonexistent ones.
- New endpoint `GET /api/tenants/entitlement-options` (global admins) feeds
  the editor; tenant CRUD validates entitlements against the global
  configuration and applies edits atomically.
