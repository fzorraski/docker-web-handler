# Configuration Reference

## Overview

Docker Web Handler is configured through `application.properties` (or equivalent Quarkus config sources). All properties can be overridden via environment variables by converting the property name to uppercase and replacing dots and hyphens with underscores.

Example: `database.dump.enabled` → `DATABASE_DUMP_ENABLED`

---

## Docker Connection

| Property | Description | Default |
|----------|-------------|---------|
| `docker.host` | Docker socket path | `unix:///var/run/docker.sock` |
| `docker.connect-timeout` | Connection timeout (ms) | 30000 |
| `docker.response-timeout` | Response timeout (ms) | 45000 |

---

## Repository Management

### Allowed Repositories

```properties
allowed.run.repositories=myapp,postgres,redis
```

Comma-separated whitelist of Docker image repositories that users can run. Only these repositories appear in the "New Container" modal dropdown.

### Registry Credentials

**Global (all repositories):**

| Property | Description |
|----------|-------------|
| `docker.registry.url` | Registry URL (default: Docker Hub) |
| `docker.registry.username` | Registry username |
| `docker.registry.password` | Registry password |

**Per-repository override:**

| Property | Description |
|----------|-------------|
| `repository.registry-url.<repo>` | Registry URL for this repo |
| `repository.registry-username.<repo>` | Username for this repo |
| `repository.registry-password.<repo>` | Password for this repo |
| `repository.registry-path.<repo>` | Full registry path (for nested paths like GitLab groups) |

Per-repository credentials support pipe-separated multi-registry configurations for repos hosted across multiple registries. An empty segment means Docker Hub.

**Registry path:** Registries with nested group/project paths (e.g., GitLab) require `registry-path` to map the short repository name to the full path used in the registry API. Without it, the app would call `/v2/myapp/tags/list` instead of `/v2/group/project/myapp/tags/list`.

**Token-based authentication:** The app automatically handles registries that require OAuth2 token exchange (GitLab, GitHub GHCR, etc.). If a registry returns a `401` with a `Www-Authenticate: Bearer` challenge, the app exchanges credentials for a Bearer token and retries.

### Example — Docker Hub + GitLab

```properties
allowed.run.repositories=myapp

# Two Docker Hub accounts + one GitLab registry
repository.registry-url.myapp=||https://registry.gitlab.com
repository.registry-path.myapp=||mygroup/myproject/myapp
repository.registry-username.myapp=hubuser1|hubuser2|gitlab-ci-token
repository.registry-password.myapp=hubpass1|hubpass2|glpat-xxxxxxxxxxxxxxxxxxxx
```

Tags from all three registries are merged into a single list in the UI. For GitLab, use `gitlab-ci-token` as the username with a Project Access Token, Deploy Token, or Personal Access Token as the password.

---

## Container Configuration

| Property | Description | Default | Per-repo override |
|----------|-------------|---------|-------------------|
| `container.default-expiration-minutes` | Default expiration for new containers | 480 (8 hours) | `repository.default-expiration-minutes.<repo>` |
| `container.memory-limit.enabled` | Show memory limit field in UI | false | `repository.memory-limit.enabled.<repo>` |
| `container.memory-limit.max-mb` | Maximum memory users can assign (MB) | 65536 | `repository.memory-limit.max-mb.<repo>` |
| `repository.env-keys.<repo>` | Pre-configured environment variables (KEY=VALUE pairs) | — | — |
| `repository.hidden-env.<repo>` | Hidden env vars (sent to container, not shown in UI) | — | — |
| `repository.java-opts-var.<repo>` | Java opts env var name for auto-heap calculation (75% Xmx / 25% Xms). Merges with existing flags — only replaces `-Xmx` and `-Xms` | — | — |

All per-repo overrides fall back to the global value when not configured.

### Example

```properties
container.default-expiration-minutes=480
container.memory-limit.enabled=true

repository.env-keys.myapp=DB_HOST=postgres,APP_ENV=dev
repository.hidden-env.myapp=SECRET_KEY=abc123
repository.java-opts-var.myapp=JAVA_OPTS
```

---

## Port Mapping

| Property | Description | Default | Per-repo override |
|----------|-------------|---------|-------------------|
| `container.port-mapping.enabled` | Enable automatic port mapping | false | `repository.port-mapping.enabled.<repo>` |
| `container.port-mapping.host-port-start` | Starting host port number | 10000 | `repository.port-mapping.host-port-start.<repo>` |
| `repository.container-ports.<repo>` | Comma-separated container ports to map | — | — |
| `repository.port-paths.<repo>` | Port:path pairs for hyperlink URLs | — | — |

### Example

```properties
container.port-mapping.enabled=true
container.port-mapping.host-port-start=10000

repository.container-ports.myapp=8080,8443
repository.port-paths.myapp=8080:/app,8443:/admin
```

When a container maps port 8080 to host port 10000, the UI renders a clickable link to `http://<host>:10000/app`.

---

## Container Expiration

| Property | Description | Default |
|----------|-------------|---------|
| `expiration.storage.file` | JSON file for expiration persistence | `data/expirations.json` |

---

## Database Features

### General

| Property | Description | Default |
|----------|-------------|---------|
| `database.listing.enabled` | Show database dropdown in container creation | false |
| `database.deletion-on-expiration.enabled` | Allow auto-delete database on container expiration | false |

### PostgreSQL Connection (per-repository)

| Property | Description |
|----------|-------------|
| `repository.pg-host.<repo>` | PostgreSQL host |
| `repository.pg-port.<repo>` | PostgreSQL port |
| `repository.pg-user.<repo>` | PostgreSQL user |
| `repository.pg-password.<repo>` | PostgreSQL password |
| `repository.pg-db-env-var.<repo>` | Env var name passed to container with the database name |
| `repository.pg-image.<repo>` | Postgres Docker image for restore/snapshot operations (use `none` for local tooling) |

Repositories with identical `pg-host` and `pg-port` share managed-database metadata (protection, creator, tenant, description) automatically; see [managed-databases.md](managed-databases.md#repositories-sharing-a-postgresql-server).

### Example

```properties
database.listing.enabled=true
database.deletion-on-expiration.enabled=true

repository.pg-host.myapp=192.168.1.100
repository.pg-port.myapp=5432
repository.pg-user.myapp=postgres
repository.pg-password.myapp=secret
repository.pg-db-env-var.myapp=DB_NAME
repository.pg-image.myapp=postgres:16
```

---

## Managed Databases

| Property | Description | Default |
|----------|-------------|---------|
| `database.managed.enabled` | Show "Databases" tab for managing live PostgreSQL databases | `false` |
| `database.managed.metadata.file` | JSON file for managed database metadata | `data/managed-databases.json` |
| `database.managed.cache.ttl-seconds` | Cache TTL for database queries (shared by all users) | `30` |

### Query Runner

| Property | Description | Default |
|----------|-------------|---------|
| `database.query.enabled` | Enable SQL query runner in the UI | `false` |
| `database.query.write-enabled` | Allow write queries (INSERT/UPDATE/DELETE) | `false` |
| `database.query.timeout-seconds` | Query execution timeout | `30` |
| `database.query.max-page-size` | Maximum rows per result page | `500` |
| `database.query.cache-total-rows` | Reuse row count from first page on subsequent pages | `true` |

### Stats Reset

| Property | Description | Default |
|----------|-------------|---------|
| `database.query-stats.reset-enabled` | Enable statistics reset buttons in database insights | `false` |

When enabled, three reset actions become available (all require operations password):

- **Reset Query Stats** (Top Queries tab) — calls `pg_stat_statements_reset()` scoped to the current database. Clears cumulative query counters (calls, total time, rows) and temp file usage stats. Table/index stats are not affected.
- **Reset Table Stats** (Tables tab) — calls `pg_stat_reset()` for the current database. Clears table and index counters (sequential scans, index scans, dead rows, vacuum timestamps). Query stats are not affected.
- **Reset Single Table Stats** (per-row button in Tables tab) — calls `pg_stat_reset_single_table_counters()` for one table. Clears only that table's counters and its indexes.

All resets are scoped to the current database — other databases on the same server are not affected.

---

## Database Dumps

| Property | Description | Default |
|----------|-------------|---------|
| `database.dump.enabled` | Enable dump feature | false |
| `database.dump.upload-password` | Password for uploading dumps | — |
| `database.dump.operations-password` | Password for restore/delete/migration operations | — |
| `database.dump.storage.dir` | Storage directory for dump files | `data/dumps/` |
| `database.dump.max-size-mb` | Maximum dump file size (MB) | 1500 |
| `database.dump.metadata.file` | JSON file for dump metadata | `data/dumps-metadata.json` |

---

## Database Snapshots

| Property | Description | Default |
|----------|-------------|---------|
| `database.snapshot.storage.dir` | Storage directory for snapshots | `data/snapshots/` |
| `database.snapshot.metadata.file` | JSON file for snapshot metadata | `data/snapshots-metadata.json` |
| `database.snapshot.max-size-mb` | Maximum snapshot size (MB) | 1500 |

---

## Post-Restore Scripts

| Property | Description | Default |
|----------|-------------|---------|
| `post-restore-scripts.enabled` | Enable post-restore SQL scripts | false |
| `post-restore-scripts.on-failure` | `stop` (abort) or `continue` (log and proceed) | `stop` |
| `repository.post-restore-mandatory-dir.<repo>` | Directory for mandatory scripts | — |
| `repository.post-restore-optional-dir.<repo>` | Directory for optional scripts | — |

---

## Database Migrations

| Property | Description | Default |
|----------|-------------|---------|
| `database.migration.enabled` | Enable migration feature | false |
| `database.migration.api-url` | Global API URL template (`{sourceVersion}`, `{targetVersion}` placeholders) | -- |
| `repository.migration-api-url.<repo>` | Per-repository API URL override | -- |

---

## Container Upgrade

| Property | Description | Default |
|----------|-------------|---------|
| `repository.upgrade-enabled.<repo>` | Enable container image tag upgrade for this repository | false |

When enabled, the **Upgrade Container** option in the action menu allows changing the container's Docker image tag. The container is stopped, removed, and recreated with the new image while preserving its configuration (name, environment variables, memory, ports, expiration, schedules).

### Example

```properties
repository.upgrade-enabled.myapp=true
repository.upgrade-enabled.postgres=false
```

See [Database Migrations](database-migrations.md#container-upgrade) for full documentation.

---

## Container Terminal

| Property | Description | Default |
|----------|-------------|---------|
| `container.terminal.enabled` | Enable the interactive terminal feature | `false` |
| `container.terminal.password` | Password for terminal access | — |
| `container.terminal.password.required` | Whether password is mandatory | `false` |
| `container.terminal.default-shell` | Preferred shell (falls back to `/bin/sh`) | `/bin/bash` |
| `container.terminal.max-sessions` | Maximum concurrent terminal sessions | `5` |
| `container.terminal.idle-timeout-minutes` | Auto-close idle sessions after this period | `30` |
| `container.terminal.upload.enabled` | Enable file upload to container from terminal | `false` |
| `container.terminal.upload.max-size-mb` | Maximum upload file size (MB) | `100` |
| `container.terminal.upload.default-path` | Default destination path inside the container | `/tmp` |
| `container.terminal.upload.image.enabled` | Enable pasting/dropping images into the terminal (independent of file upload) | `false` |
| `container.terminal.upload.image.path` | Directory inside the container for images pasted/dropped into the terminal (runtime-editable) | `/tmp` |
| `container.terminal.upload.image.path-template` | Text typed into the prompt after an image upload; `{path}` is replaced, `none` types nothing (runtime-editable; an empty override in the Settings tab also types nothing) | `{path}` |
| `container.terminal.upload.image.max-size-mb` | Maximum size (MB) for pasted/dropped images | `10` |

See [Container Terminal](container-terminal.md) for full documentation.

---

## Container Log Rotation

| Property | Description | Default | Per-repo override |
|----------|-------------|---------|-------------------|
| `container.log-rotation.enabled` | Apply log rotation to containers created by this tool | `true` | `repository.log-rotation.enabled.<repo>` |
| `container.log-rotation.max-size` | Maximum size of each log file (Docker format: `10m`, `50m`, `100m`, `1g`) | `10m` | `repository.log-rotation.max-size.<repo>` |
| `container.log-rotation.max-files` | Maximum number of log files kept per container | `3` | `repository.log-rotation.max-files.<repo>` |

Uses Docker's `json-file` log driver with `max-size` and `max-file` limits. Per-repo overrides fall back to global values.

---

## Container Memory Guard

| Property | Description | Default |
|----------|-------------|---------|
| `container.memory-guard.enabled` | Block container creation when host memory is below threshold | `true` |
| `container.memory-guard.threshold-mb` | Minimum available memory in MB to allow creation | `2048` |

Only works on Linux where `/proc/meminfo` is accessible. When triggered, the container creation is rejected with an error message showing available vs required memory.

---

## Container Scheduling

| Property | Description | Default |
|----------|-------------|---------|
| `container.scheduling.enabled` | Enable the container scheduling feature | `true` |
| `schedule.storage.file` | JSON file for persisting schedules across restarts | `data/schedules.json` |
| `container.scheduling.password` | Password for schedule operations (create, toggle, delete, execute-now) | — |
| `container.scheduling.password.required` | Whether the scheduling password is enforced | `true` |
| `schedule.executor.threads` | Thread pool size for the scheduling executor | `4` |

---

## Passwords

> **RBAC note:** when [`app.auth.mode=rbac`](#rbac-mode) is active, these
> per-feature passwords are ignored — access is governed by role permissions.

The application uses a multi-tier password system:

| Password | Used For | Property |
|----------|----------|----------|
| **Upload password** | Uploading new dumps | `database.dump.upload-password` |
| **Operations password** | Restoring dumps, deleting dumps/snapshots, running migrations, pruning images, stats reset | `database.dump.operations-password` |
| **Scheduling password** | Creating, deleting, toggling, and executing schedules | `container.scheduling.password` |
| **Terminal password** | Terminal access and file upload to containers | `container.terminal.password` |

---

## Authentication

| Property | Description | Default |
|----------|-------------|---------|
| `app.auth.enabled` | Enable login-based authentication for all API access | `false` |
| `app.auth.mode` | `password` (single shared password) or `rbac` (per-user accounts) | `password` |
| `app.auth.password` | Password for login (`password` mode only) | — |
| `app.auth.session-timeout-minutes` | Session TTL in minutes | `480` (8 hours) |
| `app.auth.session.touch-interval-seconds` | Throttle for session last-access writes | `60` |

When disabled, all endpoints are publicly accessible (backward compatible).
Sessions are stored in the application database and survive restarts.

### RBAC mode

With `app.auth.mode=rbac`, users log in with personal accounts. Users, roles
(permission sets) and tenants (team scopes, including per-tenant repository and
database-connection entitlements) are managed in the admin UI. The
[feature passwords](#passwords) are ignored in this mode — role permissions
replace them.

| Property | Description | Default |
|----------|-------------|---------|
| `rbac.admin.username` | Username of the seeded super admin | `admin` |
| `rbac.admin.password` | Seed password for the super admin. Used only on first boot while no users exist; required then, ignored afterwards. | — |

#### Built-in roles

| Role | Reach |
|---|---|
| `SUPER_ADMIN` | Everything, across all tenants. The only role that can create/edit tenants, define roles, and assign a tenant somebody does not already belong to. |
| `ADMIN` | Everything except system configuration, **limited to the tenants assigned to their own account** — they see and create users there, including other admins. |
| `OPERATOR` | Container, database, schedule, terminal and log operations in their tenants. Cannot manage users, nor delete databases, dumps or snapshots. |
| `VIEWER` | Read-only. |

A user's tenants are an explicit list of ids, never a wildcard: a tenant
created later reaches nobody until a super admin assigns it. An admin with **no**
tenant therefore sees no users and can create none — the application logs a
warning at startup naming such accounts.

Only a super admin may edit a built-in role. An edited built-in role stops
receiving permissions added in later releases (it is marked customized so the
startup re-seed leaves it alone).

Actors may only assign roles, and manage users, whose permissions they hold
themselves — otherwise handing out or taking over an account would be a way
around one's own limits.

#### Audit trail

Entries are stamped with the acting user's tenant. A reader without
cross-tenant reach sees only their own tenants' entries; entries belonging to no
tenant (system jobs, super-admin actions, anything recorded while RBAC was off)
are visible only to cross-tenant readers. Entries written before the trail
became tenant-scoped carry no tenant and are therefore super-admin-only.

---

## Admin Settings Tab

Super admins (SYSTEM_CONFIG permission) can change a subset of the properties above at runtime in the admin console under **Settings**. Overrides apply immediately, without a restart, and are stored in the application database (`runtime_settings`, see [persistence.md](persistence.md)). An override wins over both `application.properties` and environment variables until it is reset.

The tab groups settings by category and nests each setting under the flag it depends on:

```mermaid
graph TD
  subgraph Terminal
    T[Container terminal enabled] --> S[Max concurrent sessions]
    T --> I[Idle timeout]
    T --> U[File upload enabled] --> M[Upload max size]
    T --> P[Image attachments enabled] --> D[Image upload path]
    P --> X[Image path template]
  end
  subgraph "Log analyzer"
    L[Log analyzer enabled]
  end
  subgraph Sessions
    O[Session timeout]
  end
  subgraph "Audit trail"
    A[audit.enabled property, restart-only] -.-> R[Audit log retention]
  end
```

A setting whose parent flag is off is shown dimmed with a note naming that flag. It stays editable, so an admin can prepare values before enabling the parent. Image attachments depend on the terminal, not on the generic file upload. Audit retention depends on `audit.enabled`, which is a restart-only property; while it is `false` the row is shown as inactive with a note pointing to `application.properties`.

Each row is served by `GET /api/settings` with its `category`, the key it `dependsOn` (null for roots), and `disabledByProperty` (the gating property while it is off, otherwise null), so the UI never hard-codes the tree.

---

## Application Database

The app's own state (users, tenants, roles, schedules, dump/snapshot and
managed-database metadata, expirations, runtime settings, audit trail, login
sessions, counters) lives in a dedicated PostgreSQL database. The schema is
created and evolved automatically by Flyway. This database is unrelated to the
managed PostgreSQL servers configured per repository. See
[postgres-setup.md](postgres-setup.md) for setup and the one-time migration of
legacy `data/*.json` files.

| Property / env var | Description | Default |
|----------|-------------|---------|
| `APP_DB_HOST` / `APP_DB_PORT` | Database host / port | `localhost` / `5432` |
| `APP_DB_NAME` | Database name | `dockerwebhandler` |
| `APP_DB_USER` / `APP_DB_PASSWORD` | Credentials | `dockerwebhandler` |
| `persistence.backend` | `postgres` or `file` (legacy JSON fallback, one release) | `postgres` |
| `APP_DB_ACTIVE` | Set `false` (with the file backend) to boot without any PostgreSQL — deactivates the datasource and Flyway together | `true` |
| `json.import.enabled` | One-time import of legacy `data/*.json` on first boot | `true` |
| `quarkus.datasource.jdbc.max-size` | Connection pool size | `20` |

In dev and test mode no JDBC URL is set, so Quarkus Dev Services starts a
disposable PostgreSQL container automatically.

---

## Rate Limiting

Protects authentication endpoints against brute-force attacks. Applied to: login, CI API key.

| Property | Description | Default |
|----------|-------------|---------|
| `app.rate-limit.max-attempts` | Consecutive failures before backoff kicks in | `5` |
| `app.rate-limit.base-delay-seconds` | Base delay for the first backoff (doubles each subsequent failure) | `2` |
| `app.rate-limit.max-delay-seconds` | Maximum backoff delay cap | `300` (5 min) |
| `app.rate-limit.cleanup-interval-minutes` | Stale entry purge frequency | `10` |
| `app.rate-limit.entry-ttl-minutes` | Entries with no activity older than this are removed | `60` |
| `app.rate-limit.trust-forwarded-headers` | Trust `X-Forwarded-For` for client IP resolution. Enable only behind a trusted reverse proxy. | `false` |

---

## Webhook Notifications

| Property | Description | Default |
|----------|-------------|---------|
| `webhook.enabled` | Enable webhook notifications on operation completion | `false` |
| `webhook.url` | Webhook endpoint URL (required when enabled) | — |
| `webhook.secret` | Secret for HMAC-SHA256 signature (`X-Webhook-Signature` header) | — |
| `webhook.timeout-ms` | HTTP timeout in milliseconds | `5000` |
| `webhook.allow-http` | Allow HTTP (non-TLS) webhook URLs | `false` |

### Message Templates

| Property | Placeholders |
|----------|-------------|
| `webhook.template.container.success` | `{repository}`, `{tag}`, `{containerName}`, `{port}`, `{ports}`, `{timestamp}`, `{errorMessage}` |
| `webhook.template.container.failure` | Same as above |
| `webhook.template.restore.success` | `{repository}`, `{targetDatabase}`, `{dumpFilename}`, `{timestamp}`, `{errorMessage}` |
| `webhook.template.restore.failure` | Same as above |

---

## CI/CD Pipeline API

| Property | Description | Default |
|----------|-------------|---------|
| `ci.api.enabled` | Enable CI API for programmatic environment management | `false` |
| `ci.api.key` | API key for CI authentication (required when enabled) | — |
| `ci.default-ttl-minutes` | Default TTL for CI-created environments | `120` (2 hours) |
| `ci.max-ttl-minutes` | Maximum TTL for CI-created environments | `480` (8 hours) |

---

## Log Analyzer

| Property | Description | Default |
|----------|-------------|---------|
| `log.analyzer.enabled` | Enable the Log Analyzer feature | `false` |
| `log.analyzer.max-file-size-mb` | Maximum upload size per file (MB) | `500` |
| `log.analyzer.max-files` | Maximum analyses kept in memory | `5` |
| `log.analyzer.file-ttl-minutes` | Auto-eviction time (minutes) | `120` |
| `log.analyzer.slow-threshold-ms` | API call slow threshold (ms) | `1000` |
| `log.analyzer.container-tail` | Lines fetched for container analysis | `10000` |
| `log.analyzer.default-preset` | Default parsing preset | `WILDFLY` |
| `log.analyzer.parallel-threads` | Threads for parallel analysis (`auto` = CPU cores - 2) | `auto` |
| `log.analyzer.max-stored-lines` | Max raw log lines kept in memory for browsing (0 = unlimited) | `500000` |
| `log.analyzer.payload-truncate-threshold` | Max payload size (chars) in API call responses; full content available via download | `102400` |

### Analysis Feature Toggles

| Property | Description | Default |
|----------|-------------|---------|
| `log.analyzer.critical-issues.enabled` | Enable critical issues detection | `true` |
| `log.analyzer.critical-issues.java-patterns` | Include Java-specific patterns (disable for non-Java logs) | `true` |
| `log.analyzer.critical-issues.burst-threshold` | Minimum issues within burst window to form a burst | `10` |
| `log.analyzer.critical-issues.burst-window-minutes` | Time window for burst detection | `5` |
| `log.analyzer.npe-analysis.enabled` | Enable NullPointerException analysis | `true` |
| `log.analyzer.exception-analysis.enabled` | Enable generic Java exception analysis | `true` |

### Memory Limits

| Property | Description | Default |
|----------|-------------|---------|
| `log.analyzer.critical-issues.max-matches` | Total critical issue matches across all categories | `10000` |
| `log.analyzer.custom-fields.max-matches` | Stored matches per custom field | `10000` |
| `log.analyzer.npe-analysis.max-occurrences` | NPE occurrences with stack traces | `5000` |
| `log.analyzer.exception-analysis.max-occurrences` | Exception occurrences with stack traces | `5000` |

See [Log Analyzer](log-analyzer.md) for full feature documentation.

---

## Logging

| Property | Description | Default |
|----------|-------------|---------|
| `quarkus.log.file.enable` | Enable file logging | `false` |
| `quarkus.log.file.path` | Log file path | `logs/docker-web-handler.log` |
| `quarkus.log.file.rotation.file-suffix` | Rotation suffix pattern | `.yyyy-MM-dd.gz` |
| `quarkus.log.file.rotation.max-backup-index` | Rotated files to keep | `15` |

---

## UI / Server

| Property | Description | Default |
|----------|-------------|---------|
| `ui.locale` | Locale for date/time pickers (e.g., `pt-br`, `en`, `es`) | — |
| `quarkus.http.http2` | Enable HTTP/2 over plain HTTP (h2c) for multiplexed SSE streams | `true` |
| `quarkus.http.limits.max-body-size` | Maximum HTTP request body size | 5G |
| `quarkus.resteasy.path` | REST API base path | `/api` |
