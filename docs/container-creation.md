# Container Creation

## Overview

The "New Container" feature allows you to create and start Docker containers directly from the browser. It supports repository/tag selection, environment variable configuration, memory limits, expiration scheduling, database provisioning, dump restoration, and migration execution — all in a single guided flow.

The entire creation process is streamed via Server-Sent Events (SSE) so you can monitor each step in real time.

---

## Creation Flow

```
Validate → Pull Image → Create Container → Start Container → Schedule Expiration
                                                  ↓ (if database options selected)
                                          Create Database → Restore Dump/Snapshot
                                                  ↓ (if post-restore scripts configured)
                                          Run Mandatory Scripts → Run Optional Scripts
                                                  ↓ (if migration configured)
                                          Run Migration
```

---

## Configuration Options

### Repository & Tag

- **Repository:** Selected from a dropdown of allowed repositories (configured via `allowed.run.repositories`)
- **Tag:** Fetched from the Docker registry (Hub or private) for the selected repository, sorted descending by version

### Container Name

- Must be unique
- Validated for allowed characters (alphanumeric, hyphens, underscores, dots)

### Environment Variables

- Pre-populated from `repository.env-keys.<repo>` configuration
- Editable key-value rows with add/remove controls
- Hidden variables (`repository.hidden-env.<repo>`) are passed to the container but not shown in the UI

### Memory Limit

- Available when `container.memory-limit.enabled=true`
- Specified in MB
- When `repository.java-opts-var.<repo>` is configured, the system automatically calculates and sets the Java heap size (75% of the memory limit)

### Expiration

- Default duration from `container.default-expiration-minutes` (default: 480 minutes / 8 hours)
- Preset options: 1h, 4h, 8h, 24h, or custom date/time
- When the container expires, it is automatically stopped and removed
- Optionally deletes the associated database on expiration (`database.deletion-on-expiration.enabled`)

### Database Options

When `database.listing.enabled=true` and the repository has database configuration:

- **Database selector:** Choose from existing PostgreSQL databases or create a new one
- **Create database:** Checkbox to create the database if it doesn't exist
- **Delete on expiration:** Checkbox to auto-delete the database when the container expires

### Dump/Snapshot Restore

During container creation, you can optionally restore a database dump or snapshot:

- **Dump browser:** Select from previously uploaded dumps
- **Snapshot selector:** Select from existing snapshots
- After restore, post-restore scripts run automatically (mandatory) or by selection (optional)

### Migration

Optionally run SQL migrations after restore:

- **Manual mode:** Paste or upload SQL directly
- **API mode:** Specify source/target versions and fetch SQL from a configured migration API

---

## API

### Prepare (get a ticket)

```
POST /api/containers/sse/run/prepare
Content-Type: application/json

{
  "repository": "myapp",
  "tag": "1.0.0",
  "containerName": "myapp-dev",
  "envVars": { "DB_HOST": "postgres", "APP_ENV": "dev" },
  "expiresAt": "2026-03-19T18:00:00",
  "memoryMb": 512,
  "databaseName": "myapp_db",
  "createDatabase": true,
  "deleteDatabaseOnExpiration": false,
  "dumpId": "uuid-of-dump-or-null",
  "snapshotId": "uuid-of-snapshot-or-null",
  "selectedOptionalScripts": ["cleanup.sql"],
  "operationsPassword": "secret",
  "migrationMode": "API",
  "migrationSourceVersion": "1.0.0",
  "migrationTargetVersion": "1.2.0"
}
```

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Stream creation progress

```
GET /api/containers/sse/run/{ticket}
Accept: text/event-stream
```

**Events:**
- `INFO` — Step descriptions (pulling image, creating container, restoring dump, etc.)
- `PROGRESS` — Step completion with percentage
- `SUCCESS` — Container created and started successfully
- `ERROR` — Failure details

### Cancel

```
POST /api/containers/sse/run/cancel/{ticket}
```

**Response:**

```json
{ "cancelled": true }
```

---

## Port Mapping

When `container.port-mapping.enabled=true`, ports defined in `repository.container-ports.<repo>` are automatically mapped to available host ports starting from `container.port-mapping.host-port-start` (default: 10000).

Example configuration:

```properties
container.port-mapping.enabled=true
container.port-mapping.host-port-start=10000
repository.container-ports.myapp=8080,8443
repository.port-paths.myapp=8080:/app,8443:/admin
```

---

## Related Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `allowed.run.repositories` | Comma-separated allowed repositories | — |
| `container.default-expiration-minutes` | Default expiration in minutes | 480 |
| `container.memory-limit.enabled` | Show memory limit field | false |
| `container.port-mapping.enabled` | Enable auto port mapping | false |
| `container.port-mapping.host-port-start` | Starting host port | 10000 |
| `repository.env-keys.<repo>` | Pre-configured env vars | — |
| `repository.hidden-env.<repo>` | Hidden env vars | — |
| `repository.java-opts-var.<repo>` | Java opts env var name | — |
| `repository.container-ports.<repo>` | Ports to map | — |
| `repository.port-paths.<repo>` | Port:path pairs | — |
