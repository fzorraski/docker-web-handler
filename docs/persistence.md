# Persistence Layer

The application stores its own state in a dedicated **PostgreSQL** database.
This replaced the original JSON files under `data/` (see
[postgres-persistence-migration-plan.md](postgres-persistence-migration-plan.md)
for the design rationale). Operator instructions live in
[postgres-setup.md](postgres-setup.md); this document describes the
architecture for developers.

## What is stored where

| State | PostgreSQL table | Legacy JSON file |
|---|---|---|
| Users | `app_user` | `data/users.json` |
| Tenants (incl. repository/database entitlements) | `tenant` | `data/tenants.json` |
| Roles | `role` | `data/roles.json` |
| Container schedules | `container_schedule` | `data/schedules.json` |
| Managed database metadata | `managed_database` | `data/managed-databases.json` |
| Dump metadata | `database_dump` | `data/dumps-metadata.json` |
| Snapshot metadata | `database_snapshot` | `data/snapshots-metadata.json` |
| Container expirations | `container_expiration` | `data/expirations.json` |
| Database migration records | `database_migration` | `data/migrations.json` |
| Runtime setting overrides | `runtime_settings` (single row) | `data/settings.json` |
| Audit trail | `audit_log` | `data/audit.log` |
| Login sessions | `auth_session` | *(memory only — new)* |
| Lifetime resource counters | `resource_counter` | `data/resource-counters.json` |
| Image last-used tracking | `image_usage` | `data/image-usage.json` |
| Import bookkeeping | `json_import_history` | — |

**Still on disk:** dump/snapshot *binaries* (`data/dumps/`, `data/snapshots/`),
operator-provisioned post-restore SQL scripts, and log-analyzer scratch files.

## Architecture

```
application/port/*Repository          <- contracts (unchanged by the migration)
        ▲
        │ selected by PersistenceBackendProducer (persistence.backend)
        │
infrastructure/persistence/JsonFile*  <- legacy file backend (fallback, one release)
infrastructure/persistence/jdbc/Pg*   <- PostgreSQL backend (default)
```

- **`PersistenceBackendProducer`** produces one bean per port and picks the
  implementation from `persistence.backend` (`postgres` | `file`). Both
  implementation sets are `@Typed` to their concrete class, so the producer is
  the only provider of each port interface and `@Inject SomeRepository`
  everywhere stays untouched.
- **`JdbcSupport`** is the shared JDBC plumbing: parameter binding (including
  `jsonb` and `Instant`), row mapping, manual transactions for mutators, and
  the translation of unique-constraint violations (SQLState 23505) into the
  domain's `DuplicateEntityException` so `GlobalExceptionMapper` renders races
  exactly like the use-case pre-checks.
- **Schema** is owned by Flyway: `src/main/resources/db/migration/`. The app's
  datasource is the Quarkus default datasource — completely separate from
  `DatabaseService`, which opens raw JDBC connections to the *managed*
  PostgreSQL servers configured per repository.
- Nested collections are `jsonb` columns (role permissions, user role/tenant
  ids, tenant entitlements, dump/snapshot sharing lists, the whole
  `RunContainerConfig` of a schedule). There are deliberately **no join
  tables**: `AuthorizationService` loads everything into a snapshot anyway,
  and dangling ids are skipped at resolve time (pre-existing semantics).

## Concurrency guarantees

The migration was designed for 100+ concurrent users. What changed:

| Flow | Before (JSON files) | Now (PostgreSQL) |
|---|---|---|
| Duplicate user/tenant/role names | check-then-act race across the file lock | `UNIQUE INDEX ON lower(name)` → `DuplicateEntityException` |
| Dump dedup (md5 / filename) | check-then-act race | `UNIQUE` constraints |
| `update(id, mutator)` (user, tenant, role, dump, snapshot) | atomic within one JVM only | `SELECT … FOR UPDATE` transaction — atomic across processes |
| Schedule execution write-back | full-object save could clobber concurrent admin edits | targeted `UPDATE` of the 4 execution columns (`ScheduleRepository.recordExecution`) |
| Counter increment | whole-file rewrite per increment | single atomic `UPDATE … + 1` |
| Activity poller `bulkMarkUsed` (every 60 s) | whole-file rewrite | one transaction, timestamps never regress |
| Login sessions | in-memory, lost on restart | durable `auth_session` rows, SHA-256-hashed tokens |

**Transaction policy:** repositories own their transactions; use cases are
**never** annotated `@Transactional` — container runs hold operations open for
minutes across Docker calls, and pinning a pool connection that long would
starve the pool (default `quarkus.datasource.jdbc.max-size=20`).

## Caching

Per-request authorization does not hit the database:

- `AuthorizationService` keeps a volatile snapshot of all users, roles, and
  tenants; every management write calls `invalidateCache()`.
- `TenantVisibility` and `TenantEntitlements` read tenants through that
  snapshot (`tenantById`/`allTenants`) — never per-check queries.
- `RuntimeSettingsService` caches the settings row the same way.
- `AuthSessionManager` fronts the session store with a ~30 s read cache and
  throttles `last_accessed_at` writes to one per minute per session
  (`app.auth.session.touch-interval-seconds`). Local invalidations purge the
  cache immediately, so revocation is instant on a single instance.

**Multi-instance status:** the data layer is safe across processes, but
cache invalidation coherence (e.g. PG `LISTEN/NOTIFY`), leader election for
the in-JVM schedulers/expiry timers, and `PortFinder` reservations are still
single-node. Documented as follow-up work in the migration plan.

## One-time JSON import

`infrastructure/persistence/jdbc/JsonDataImporter` observes `StartupEvent`
with `@Priority(1)` — it is guaranteed to finish **before** the scheduler,
expiration, and dump/snapshot services load their timers from the store.

Per store, in dependency order (roles → tenants → users → the rest):

1. Skipped when a `json_import_history` marker exists, the target table
   already has rows, or the JSON file is absent.
2. Reads through the legacy `JsonFile*` repositories (identical JSON-B
   binding to what wrote the files) and writes through the `Pg*` repositories.
3. Records the marker and renames the source file to `*.imported`.
4. On failure the partially imported table is wiped and **startup aborts** —
   a half-imported auth store is worse than a crash loop; the next boot
   retries cleanly.

The import runs only in prod mode (`%dev`/`%test` set
`json.import.enabled=false`) so a throwaway Dev Services database can never
consume — and rename — a developer's real `data/` files.

`data/audit.log` history is intentionally not imported; the file stays
readable on disk.

## Testing

- `@QuarkusTest` integration tests in
  `src/test/java/br/com/fzdevx/infrastructure/persistence/jdbc/` run against
  **Quarkus Dev Services** (a disposable PostgreSQL container started
  automatically — no manual setup, no Testcontainers dependency).
- `PostgresBackendProfile` boots the app with `persistence.backend=postgres`;
  `JsonImportProfile` seeds legacy files into `target/` and enables the
  importer. **Any new import test must override every `*.file` property** —
  otherwise it reads (and renames!) the developer's real `data/` directory.
- Concurrency is tested for real: parallel same-name creates (exactly one
  winner), parallel field edits via mutators (both survive), parallel counter
  increments (exact total).
- The `JsonFile*` unit tests (`@TempDir`) remain and guard the `file`
  fallback until it is removed.

## Removal plan for the file backend

The `file` backend exists as an escape hatch for one release. The cleanup
(delete `JsonFile*`, `AbstractJsonFileRepository`, `AtomicFileWriter`,
`InMemorySessionRepository`, the producer switch, and port the `@TempDir`
tests) is Phase 8 of the migration plan.
