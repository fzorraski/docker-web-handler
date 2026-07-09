# PostgreSQL Persistence Migration Plan

Branch: `features/postgres-persistence` · Quarkus 3.32.2 · Java 25

> **Status: implemented** (Phases 0–7). This document is the original design
> plan, kept for rationale and history. The authoritative documentation of the
> persistence layer as built is [persistence.md](persistence.md); operator
> setup lives in [postgres-setup.md](postgres-setup.md). Phase 8 (removal of
> the legacy file backend) is scheduled for the release after the migration
> ships.

## Why

All application state lives in JSON files under `data/` written by `AbstractJsonFileRepository` (whole-file rewrite per mutation, `ReentrantReadWriteLock`) plus a handful of ad-hoc file writers. With 100+ concurrent users this has real limits:

- **Locks are per-JVM.** Every atomicity guarantee is single-node; a second instance corrupts data.
- **Check-then-act races.** Duplicate-name checks (users, tenants, roles), dump dedup (filename + SHA-256), and counter increments are not atomic across the lock boundary.
- **Whole-file rewrites on hot paths.** `DatabaseActivityPoller` rewrites `managed-databases.json` every 60s; `ResourceCounterService` rewrites its file on every increment; the audit log appends under a process-wide lock on every mutating action.
- **Sessions are in-memory only** — every restart logs all users out.
- **Last-write-wins clobbering.** Role edits, schedule execution write-back, expirations, and settings do read-then-full-save.

## Current persistence inventory (exhaustive)

| Store | File | Port | Write freq | Notes |
|---|---|---|---|---|
| Users | `data/users.json` | `UserRepository` | low | atomic `update(id, mutator)`; unique username (case-insensitive) enforced by check-then-act |
| Tenants | `data/tenants.json` | `TenantRepository` | low | atomic mutator; **read per authorization check, uncached (hot)** via `TenantVisibility`/`TenantEntitlements` |
| Roles | `data/roles.json` | `RoleRepository` | low | full-object save (last-write-wins) |
| Schedules | `data/schedules.json` | `ScheduleRepository` | med | nested `RunContainerConfig` (lists + map); loaded into in-JVM scheduler at startup; status write-back clobbers concurrent edits |
| Managed DBs | `data/managed-databases.json` | `ManagedDatabaseRepository` | **high** | composite case-insensitive id (repository, name); `bulkMarkUsed` rewrite every 60s (poller) |
| Dump metadata | `data/dumps-metadata.json` | `DumpRepository` | med | atomic mutator; racy dedup by originalFilename + md5Hash |
| Snapshot metadata | `data/snapshots-metadata.json` | `SnapshotRepository` | med | atomic mutator |
| Expirations | `data/expirations.json` | `ExpirationRepository` | med | full save; in-JVM expiry timers reload on startup |
| Migration records | `data/migrations.json` | *(none — concrete `JsonFileMigrationRepository`)* | low | composite (databaseName, repository) upsert |
| Runtime settings | `data/settings.json` | `SettingsRepository` | low | single object; cached in `RuntimeSettingsService` |
| Audit log | `data/audit.log` | `AuditLogger` | **high** | append-only JSONL + process lock; retention rewrites file |
| Resource counters | `data/resource-counters.json` | — (`ResourceCounterService`) | med | whole-file rewrite per increment |
| Image usage | `data/image-usage.json` | — (`ImageUsageTracker`) | med | bulk upsert + retainAll cleanup |
| **Auth sessions** | **in-memory only** (`AuthSessionManager`) | — | very high | lost on restart; must become durable |

**Stays on disk (out of scope):** dump/snapshot binary blobs (`data/dumps/`, `data/snapshots/` — only metadata moves to PG), operator-provisioned post-restore SQL script dirs, log-analyzer temp files.

**Existing infra:** none. `pom.xml` has only the raw `org.postgresql` driver used by `DatabaseService` (raw `DriverManager` connections to the *managed* PG servers — unrelated code, must not change). No Agroal, no ORM, no Flyway, no Testcontainers. Per-request auth does **not** hit the repos today — `AuthorizationService` keeps a volatile snapshot of users+roles+tenants, invalidated after each write; `RuntimeSettingsService` does the same for settings. That pattern is kept.

## Design decisions

### D1 — Plain JDBC over Agroal (no ORM)
Add `quarkus-jdbc-postgresql`, `quarkus-agroal`, `quarkus-flyway`, `quarkus-narayana-jta`. The app's own store uses the **Quarkus default datasource** (no collision — `DatabaseService` bypasses Agroal entirely). Rejected Hibernate/Panache: the domain must stay framework-free, so an ORM means 11 duplicate entity classes + mappers — more code than hand-written JDBC repos; the nested collections map naturally to `jsonb`; and the correctness features needed (FOR UPDATE, ON CONFLICT upserts) are plain SQL anyway.

New shared helper `infrastructure/persistence/jdbc/JdbcSupport.java`: query/update lambdas over `AgroalDataSource`, `Instant`↔`timestamptz` conversion, shared `Jsonb` for jsonb columns, and **SQLState 23505 → `DuplicateEntityException`** mapping (picked up by the existing `GlobalExceptionMapper`). PG repos live in `infrastructure/persistence/jdbc/` as `PgUserRepository`, `PgTenantRepository`, …

### D2 — Flyway, single `V1__baseline.sql`
`src/main/resources/db/migration/V1__baseline.sql`. All ids `text` (existing UUID strings), timestamps `timestamptz`. Nested lists/sets/config → `jsonb` columns, **no join tables** (`AuthorizationService` loads everything into a snapshot anyway; deleted role/tenant ids are intentionally skipped at resolve time — current semantics).

| Table | Key constraints |
|---|---|
| `app_user` (role_ids/tenant_ids jsonb) | `UNIQUE INDEX ON (lower(username))` |
| `tenant` (enabled_repositories/enabled_databases jsonb) | `UNIQUE (lower(name))` |
| `role` (permissions jsonb) | `UNIQUE (lower(name))` |
| `container_schedule` (create_config jsonb) | index (enabled) |
| `managed_database` PK (repository, name) | `UNIQUE INDEX ON (lower(repository), lower(name))` |
| `database_dump` (shared_with_tenants jsonb) | `UNIQUE (md5_hash)`, `UNIQUE (original_filename)` |
| `database_snapshot` (shared_with_tenants jsonb) | index (expires_at) |
| `container_expiration` PK short_id | index (expires_at) |
| `database_migration` PK (database_name, repository) | — |
| `runtime_settings` single row (`id int PK CHECK (id=1)`) | — |
| `audit_log` (bigserial, occurred_at, actor, action, target, detail) | index (occurred_at) |
| `auth_session` PK token_hash | index (last_accessed_at) |
| `resource_counter` PK counter_key | — |
| `image_usage` PK image_id | — |
| `json_import_history` PK store | importer marker |

### D3 — One-time JSON import on startup
`infrastructure/persistence/jdbc/JsonDataImporter.java` observes `StartupEvent` with high `@Priority` (must run before `ContainerSchedulingService` / `ContainerExpirationService` / dump-snapshot expiry services load their in-JVM timers — verify and pin their observer priorities). Per store, in dependency order (roles → tenants → users → settings → managed DBs → dumps → snapshots → schedules → expirations → migrations → counters → image usage):

1. Skip if `json_import_history` has the store, the table is non-empty, or the file is absent.
2. Read via the existing JSON-B model bindings (reuse the `JsonFile*` list types — field compatibility guaranteed).
3. Insert rows + marker row in one `REQUIRES_NEW` transaction per store.
4. Rename `users.json` → `users.json.imported` (rename failure logged, non-fatal — marker prevents re-import).

Partial failure → rollback of that store and **fail-fast abort of startup** (a half-imported auth store is worse than a crash loop). `audit.log` history is *not* imported — old file stays on disk. Fresh install (no files, empty DB) falls through to the existing `RbacBootstrap` seeding.

### D4 — Durable sessions in PG
`application/port/SessionRepository` + `PgSessionRepository`; `AuthSessionManager` becomes a caching facade:
- Store **SHA-256(token)** as PK (raw UUID only ever goes to the client).
- In-memory read cache (`ConcurrentHashMap`, ~30s TTL) so `validateAndTouch` does not SELECT per request.
- **Touch throttling**: UPDATE `last_accessed_at` only when the cached value is older than 60s (`app.auth.session.touch-interval-seconds`). ~100 users ⇒ ~100 tiny UPDATEs/min worst case.
- Eviction: existing 5-min daemon becomes `DELETE ... WHERE last_accessed_at < cutoff`. Idle timeout still from `RuntimeSettingsService`.
- `invalidateByUser` etc. → DELETE + local cache purge. (Multi-instance revocation lag is bounded by the cache TTL — documented limitation.)

### D5 — Audit log as a table
`PgAuditLogger`: plain columns (fixed shape today), **synchronous single INSERT** in try/catch that logs-but-never-throws (audit failure must not fail the action — file parity). `AuditRetentionService` → `DELETE WHERE occurred_at < cutoff` on its existing schedule.

### D6 — Counters & image usage as atomic SQL
- `increment(key)` → `INSERT ... ON CONFLICT (counter_key) DO UPDATE SET counter_value = counter_value + 1`.
- Image usage bulk mark → multi-row `INSERT ... ON CONFLICT DO UPDATE SET last_used_at = GREATEST(...)`; cleanup → `DELETE WHERE image_id <> ALL (?)`.

### D7 — Caching stays; hot path fixed
Keep the `AuthorizationService` / `RuntimeSettingsService` volatile-snapshot + `invalidate()` pattern. **Fix**: expose `tenantById()` / `allTenants()` from the AuthorizationService snapshot and rewire `TenantVisibility` and `TenantEntitlements` to it — kills the per-authorization-check `tenants.json`/DB read. Deferred (documented): multi-instance cache coherence via PG `LISTEN/NOTIFY` or snapshot TTL.

### D8 — Transaction strategy
- Single statements on auto-commit; **no `@Transactional` on use cases** (`RunContainerUseCase` holds operations open for minutes across Docker calls — a pinned connection starves a 20-conn pool at 100 users).
- Port mutators `update(id, Consumer<T>)` (User, Tenant, Dump, Snapshot) → `@Transactional` repo method: `SELECT ... FOR UPDATE` → materialize → mutate → full-row UPDATE. Same contract as today, now cross-process safe.
- Duplicate-name / dedup flows: keep the friendly pre-check, but the **UNIQUE constraint is the real guard** (23505 → `DuplicateEntityException`).
- `RoleRepository` gains `update(id, mutator)` (closes the role-edit clobber); `ScheduleRepository` gains `recordExecution(id, status, message, lastExecutedAt, nextExecutionAt)` as a targeted 4-column UPDATE so an execution can never clobber a concurrent admin edit (real bug today). Expiration/settings stay upsert-full-row (single-writer in practice).
- `bulkMarkUsed` → one `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE app_last_used_at < EXCLUDED.app_last_used_at` statement.

### D9 — Config & dev experience
```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${APP_DB_USER:dockerwebhandler}
quarkus.datasource.password=${APP_DB_PASSWORD:dockerwebhandler}
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://${APP_DB_HOST:localhost}:${APP_DB_PORT:5432}/${APP_DB_NAME:dockerwebhandler}
quarkus.datasource.jdbc.max-size=20
quarkus.flyway.migrate-at-start=true
```
Dev/test: the `%prod`-scoped URL is absent → **Quarkus Dev Services** spins up a disposable PG container automatically (Docker is already a project requirement). Integration tests are `@QuarkusTest` on Dev Services — no explicit Testcontainers dependency needed. Compose: add a `postgres:17-alpine` sidecar with volume + healthcheck + `depends_on: service_healthy` (`docker-compose.yml` is never committed — ship as `docs/postgres-setup.md` + `docker-compose.example.yml`).

### D10 — Rollout with an escape hatch
Runtime switch `persistence.backend=file|postgres` (default `postgres` once the importer lands), implemented as a `PersistenceBackendProducer` with `@Produces` methods per port choosing `JsonFile*` or `Pg*` (impls lose `@ApplicationScoped`; injection points stay `@Inject UserRepository`). `file` remains the field fallback for one release; Phase 8 deletes it.

## Phases (each = one committable feature)

| Phase | Scope | ~Effort |
|---|---|---|
| 0 Foundations | pom deps, datasource config, `V1__baseline.sql`, `JdbcSupport`, new `MigrationRecordRepository` port (pure refactor), Dev Services smoke test | 1d |
| 1 Low-risk stores | `PgSettingsRepository`, `PgMigrationRecordRepository`, `PgExpirationRepository`, counters + image usage → SQL | 1d |
| 2 Identity + cache fix | `PgRoleRepository` (+ new mutator), `PgTenantRepository`, `PgUserRepository`; `TenantVisibility`/`TenantEntitlements` → snapshot; parallel-create concurrency tests | 2d |
| 3 Operational metadata | `PgManagedDatabaseRepository` (bulkMarkUsed), `PgDumpRepository`/`PgSnapshotRepository` (constraint-backed dedup), `PgScheduleRepository` + `recordExecution` | 2d |
| 4 Audit | `PgAuditLogger`, retention → SQL DELETE | 0.5d |
| 5 Durable sessions | `SessionRepository`, `PgSessionRepository`, `AuthSessionManager` facade (hash/cache/throttle/eviction); restart-survival tests | 1d |
| 6 Importer + switch | `JsonDataImporter`, `PersistenceBackendProducer`, default → `postgres`; import integration tests (seed files → boot → rows + `.imported` + idempotent re-boot) | 1.5d |
| 7 Docs & ops | `docs/postgres-setup.md`, `docker-compose.example.yml`, env reference, backup note (pg_dump replaces "copy data/") | 0.5d |
| 8 Cleanup (next release) | delete `JsonFile*` + `AbstractJsonFileRepository`/`AtomicFileWriter` + producer switch; port the `@TempDir` repo tests to `@QuarkusTest`. Until then all existing tests stay green untouched (none are `@QuarkusTest` today) | 1d |

## Concurrency guarantees: before → after

| Flow | Before (JSON files) | After (PG) |
|---|---|---|
| Duplicate user/tenant/role name | check-then-act race | `UNIQUE lower(name)` → `DuplicateEntityException` |
| Dump dedup (md5/filename) | check-then-act race | UNIQUE constraints |
| `update(id, Consumer)` mutators | atomic per-JVM only | `FOR UPDATE` transaction, cross-process |
| Role edit / schedule status write-back | last-write-wins clobber | mutator method / targeted 4-column UPDATE |
| Counter increment | whole-file rewrite | atomic `UPDATE +1` |
| `bulkMarkUsed` (60s poller) | whole-file rewrite | single upsert statement |
| Sessions | lost on restart | durable, hashed token, throttled touch |
| Multi-instance | impossible | data layer safe; **deferred**: cache invalidation coherence (LISTEN/NOTIFY), leader election for in-JVM schedulers/expiry timers (would double-fire), `PortFinder` reservations, session-revocation lag ≤ cache TTL |

## Verification

1. Per-phase: `./mvnw test` stays green (2026 tests); new `@QuarkusTest` integration tests per repo against Dev Services PG.
2. Concurrency tests: N parallel same-name creates → exactly 1 success; parallel mutator edits of different fields → both persist; parallel counter increments → exact total.
3. Import test: copy a real `data/` dir → boot → assert row counts match file entries, files renamed, second boot no-op, login works with pre-existing users.
4. End-to-end: compose stack with PG sidecar, RBAC login, create tenant/user, run container, upload dump, restart the app container → sessions survive, data intact.
5. Load sanity: hit auth + container list with ~100 concurrent sessions (e.g. `hey`/`wrk`), verify pool metrics (Agroal) show no exhaustion.

## Risks & open questions

- `RunContainerConfig.operationsPassword` sits inside the `create_config` jsonb — plaintext parity with today; consider encrypting as a follow-up.
- Startup ordering: importer must complete before scheduler/expiry services load timers — pin observer priorities and test.
- Native build (`-Pnative`): Flyway + Agroal are supported, but run a native smoke test (jsonb + JSON-B reflection).
- CI needs Docker for Dev Services (already required by the project; confirm the runner).
- Decide: also make snapshot `md5_hash` unique, or dumps only (today only dumps dedup by hash)?
