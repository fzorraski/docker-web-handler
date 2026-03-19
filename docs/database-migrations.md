# Database Migrations

## Overview

Docker Web Handler supports running SQL migration scripts against PostgreSQL databases. Migrations can be executed as part of a container creation, dump restore, or as a standalone operation. Two modes are available: **Manual** (paste SQL directly) and **API** (fetch SQL from an external migration service).

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

Migrations can run in three contexts:

### 1. During Container Creation

When creating a new container with a database, you can configure a migration step that runs after the dump/snapshot restore and post-restore scripts.

### 2. During Dump Restore

When restoring a dump as a standalone operation, you can configure a migration step that runs after the restore and post-restore scripts.

### 3. Standalone Migration

Run a migration directly on an existing database without creating a container or restoring a dump. This is useful for upgrading a database that's already in use.

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

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `database.migration.enabled` | Enable the migration feature | false |
| `database.migration.api-url` | Global API URL template (placeholders: `{sourceVersion}`, `{targetVersion}`) | — |
| `repository.migration-api-url.<repo>` | Per-repository API URL override | — |

---

## UI

### Standalone Migration

1. Click the **Run Migration** button on a container row in the containers table
2. Select the repository and target database
3. Choose Manual or API mode
4. In API mode, enter source/target versions and optionally preview the SQL
5. Enter the operations password
6. Click Run — progress is streamed in real time

### Migration During Restore/Creation

1. In the Restore Dump or New Container modal, enable the **Migration** toggle
2. A configuration dialog opens with mode selection (Manual/API)
3. Configure the migration parameters
4. The migration step runs automatically after the restore completes
