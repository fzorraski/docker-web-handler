# Database Migrations

## Overview

Docker Web Handler supports running SQL migration scripts against PostgreSQL databases. Migrations can be executed as part of a container creation, dump restore, container upgrade, or as a standalone operation. Two modes are available: **Manual** (paste SQL directly) and **API** (fetch SQL from an external migration service).

For details on implementing the external migration API, see [migration-api.md](migration-api.md).

---

## Migration Modes

### Manual Mode

Paste or type SQL directly into the text area. The SQL is executed as-is via `psql` against the target database.

Use this when you have a one-off migration script or want to run specific SQL statements.

### API Mode

Specify a source version and target version. The application calls a configured external API to fetch the migration SQL, then executes it.

Use this when your project has a migration service that can generate incremental SQL based on version ranges.

API mode is only available when `database.migration.api-url` or `repository.migration-api-url.<repo>` is configured.

---

## Migration Contexts

Migrations can run in four contexts:

### 1. During Container Creation

When creating a new container with a database, you can configure a migration step that runs after the dump/snapshot restore and post-restore scripts.

A version hint is shown when the dump version is older than the selected image tag, suggesting the user enable migration.

### 2. During Dump Restore

When restoring a dump as a standalone operation, you can configure a migration step that runs after the restore and post-restore scripts.

### 3. Container Upgrade

When upgrading a container to a new image tag, you can optionally include a database migration as part of the upgrade process. See the [Container Upgrade](#container-upgrade) section below.

### 4. Standalone Migration

Run a migration directly on an existing database without creating a container or restoring a dump. This is available through the **Upgrade Container** dialog by leaving the tag unchanged and configuring only the migration.

---

## Container Upgrade

The **Upgrade Container** feature allows you to change a container's Docker image tag and optionally run a database migration — all in a single operation.

### How It Works

Since Docker does not allow changing a container's image in-place, the upgrade process:

1. **Inspects** the running container to extract its configuration (name, environment variables, memory limit, labels, port bindings)
2. **Pulls** the new image tag from the registry
3. **Stops** the old container
4. **Removes** the old container
5. **Creates** a new container with the same configuration but the new image
6. **Starts** the new container
7. **Runs migration** (optional) against the associated database
8. **Transfers metadata** — expiration settings and schedules are moved to the new container

### Port Handling

The upgrade process attempts to reuse the same host ports from the old container. If any port is unavailable (e.g., briefly occupied during the transition), new ports are automatically allocated.

### Failure Handling

- If failure occurs **before** the old container is removed (e.g., image pull fails): the old container is untouched
- If failure occurs **after** the old container is removed (e.g., new container fails to start): an error message is shown indicating the old container was removed
- If **migration fails** after the container is successfully upgraded: the upgrade completes with a warning, and the migration can be retried manually

### Per-Repository Configuration

Container upgrade must be explicitly enabled per repository:

```properties
repository.upgrade-enabled.myapp=true
```

When upgrade is disabled for a repository, the tag selector is hidden in the dialog, but standalone migration is still available if `database.migration.enabled=true`.

---

## Migration Flow

```
Validate Request → Resolve SQL (manual or fetch from API)
    → Execute SQL via psql → Record Migration History
```

### Execution

- SQL is written to a temporary `.sql` file
- Executed via `psql -f` inside a temporary Postgres container (or locally if `pg-image` is `none`)
- All `psql` output is streamed to the progress log in real time
- The temporary container is cleaned up after execution

### Migration History

Every migration execution is recorded with:
- Target database name
- Repository
- Source and target versions
- Migration mode (MANUAL or API)
- Timestamp
- SQL executed

History can be viewed per container from the containers table.

---

## API Endpoints

### Check Feature Enabled

```
GET /api/containers/migration-enabled
```

**Response:** `true` or `false`

### Check API Available

```
GET /api/containers/migration-api-available?repository=myapp
```

**Response:** `true` or `false` (whether a migration API URL is configured for the repository)

### Preview Migration (API Mode)

```
GET /api/containers/migration-preview?repository=myapp&sourceVersion=1.0.0&targetVersion=1.2.0
```

**Response:**

```json
{
  "sql": "ALTER TABLE users ADD COLUMN email VARCHAR(255);...",
  "sourceVersion": "1.0.0",
  "targetVersion": "1.2.0",
  "totalStatements": 5,
  "versionsIncluded": ["1.1.0", "1.2.0"]
}
```

### Get Migrated Databases

```
GET /api/containers/migrated-databases
```

**Response:** Array of `DatabaseMigrationRecord` objects with migration history.

### Prepare Standalone Migration

```
POST /api/containers/sse/migration/prepare
Content-Type: application/json

{
  "repository": "myapp",
  "targetDatabase": "myapp_db",
  "password": "operations-password",
  "migrationMode": "API",
  "migrationSourceVersion": "1.0.0",
  "migrationTargetVersion": "1.2.0"
}
```

For manual mode:

```json
{
  "repository": "myapp",
  "targetDatabase": "myapp_db",
  "password": "operations-password",
  "migrationMode": "MANUAL",
  "migrationSql": "ALTER TABLE users ADD COLUMN email VARCHAR(255);"
}
```

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Stream Migration

```
GET /api/containers/sse/migration/{ticket}
Accept: text/event-stream
```

**Events:**
- `INFO` — Progress messages (validating, fetching SQL, executing)
- `PROGRESS` — Step completion updates
- `SUCCESS` — Migration applied successfully
- `ERROR` — Failure details (SQL errors, API errors, etc.)

### Cancel Migration

```
POST /api/containers/sse/migration/cancel/{ticket}
```

### Prepare Container Upgrade

```
POST /api/containers/sse/upgrade/prepare
Content-Type: application/json

{
  "containerId": "abc123...",
  "newTag": "20.88.3",
  "password": "operations-password",
  "migrationMode": "API",
  "migrationSourceVersion": "20.88.2",
  "migrationTargetVersion": "20.88.3"
}
```

For upgrade without migration, omit the migration fields. For migration-only (no tag change), omit `newTag`.

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Stream Upgrade

```
GET /api/containers/sse/upgrade/{ticket}
Accept: text/event-stream
```

**Events:** Same as migration stream, plus additional steps: `Inspecting`, `Pulling`, `Stopping`, `Removing`, `Creating`, `Starting`.

### Cancel Upgrade

```
POST /api/containers/sse/upgrade/cancel/{ticket}
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `database.migration.enabled` | Enable the migration feature | false |
| `database.migration.api-url` | Global API URL template (placeholders: `{sourceVersion}`, `{targetVersion}`) | -- |
| `repository.migration-api-url.<repo>` | Per-repository API URL override | -- |
| `repository.upgrade-enabled.<repo>` | Enable container upgrade (tag change) for this repository | false |

---

## UI

### Container Upgrade

1. Click **Upgrade Container** in the container action menu (three-dot menu)
2. Select a new image tag from the dropdown (sorted newest first)
3. Optionally enable **Run database migration** and configure it (Manual or API mode)
4. Enter the operations password
5. Click **Upgrade** — progress is streamed in real time with steps: Validating, Inspecting, Pulling, Stopping, Removing, Creating, Starting, Running Migration

The upgrade option is only visible when `repository.upgrade-enabled.<repo>=true` is set, or when migration is available for the container's database.

### Migration During Restore/Creation

1. In the Restore Dump or New Container modal, enable the **Migration** toggle
2. A configuration dialog opens with mode selection (Manual/API)
3. Configure the migration parameters
4. The migration step runs automatically after the restore completes

When restoring a dump whose version is older than the selected image tag, an info alert suggests enabling migration.
