# Changelog

## Unreleased

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
