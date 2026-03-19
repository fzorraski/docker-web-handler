# Database Snapshots

## Overview

Database snapshots allow you to capture the current state of a PostgreSQL database and save it for later use or download it immediately. Snapshots are created using `pg_dump` (executed via a temporary Postgres Docker container or local tooling) and can be stored in either SQL or custom (binary) format.

---

## Features

### Create Snapshot

Create a snapshot of any configured PostgreSQL database:

- **Repository:** Select which repository's database configuration to use
- **Source database:** Select the database to snapshot
- **Format:** `SQL` (plain text) or `CUSTOM` (binary, smaller, supports parallel restore)
- **Label:** Optional short label for identification
- **Description:** Optional longer description
- **Expiration:** Optional auto-deletion date
- **Save or download:**
  - **Save:** Store the snapshot on the server for later download or deletion
  - **Download direct:** Stream the snapshot directly to your browser without storing it

Snapshot creation progress is streamed via SSE.

### List Snapshots

All saved snapshots are displayed in a table:

| Column | Description |
|--------|-------------|
| Database | Source database name |
| Repository | Repository configuration used |
| Format | SQL or CUSTOM |
| Label | User-provided label |
| Created | Creation timestamp |
| Size | File size (formatted) |
| Expiration | Auto-deletion date (if set) |
| Last Used | Last download timestamp |
| Actions | Download, Delete, Edit Expiration |

### Download Snapshot

Download any stored snapshot file. Updates the `lastUsedAt` timestamp for idle tracking.

### Delete Snapshots

- **Single delete:** Remove one snapshot by ID
- **Bulk delete:** Select multiple snapshots via checkboxes and delete all at once
- **Cleanup idle:** Automatically delete snapshots that haven't been downloaded for a specified number of days

All delete operations require the operations password.

### Edit Expiration

Set, change, or remove the auto-deletion date for a snapshot.

### Storage Info

Displays current snapshot storage usage (total bytes, file count, max bytes).

---

## API Endpoints

### List Snapshots

```
GET /api/database/snapshots/list
```

**Response:** Array of `DatabaseSnapshot` objects.

### Download Snapshot

```
GET /api/database/snapshots/download/{id}
```

**Response:** File download.

### Download Direct (no storage)

```
POST /api/database/snapshots/download-direct
Content-Type: application/json

{
  "repository": "myapp",
  "sourceDatabaseName": "myapp_db",
  "format": "CUSTOM",
  "password": "operations-password"
}
```

**Response:** File stream.

### Delete Snapshot

```
DELETE /api/database/snapshots/delete/{id}
X-Dump-Password: operations-password
```

### Bulk Delete

```
DELETE /api/database/snapshots/delete/bulk
X-Dump-Password: operations-password
Content-Type: application/json

["uuid-1", "uuid-2"]
```

### Update Expiration

```
PUT /api/database/snapshots/expiration/{id}
X-Dump-Password: operations-password
Content-Type: application/json

{ "expiresAt": "2026-04-19T00:00:00" }
```

### Cleanup Idle

```
POST /api/database/snapshots/cleanup-idle
Content-Type: application/json

{ "password": "operations-password", "minDays": 30 }
```

### Storage Info

```
GET /api/database/snapshots/storage-info
```

### Prepare Snapshot (Save)

```
POST /api/database/snapshots/sse/create/prepare
Content-Type: application/json

{
  "repository": "myapp",
  "sourceDatabaseName": "myapp_db",
  "format": "CUSTOM",
  "label": "before-migration",
  "description": "Snapshot before v2 migration",
  "expiresAt": "2026-04-19T00:00:00",
  "password": "operations-password"
}
```

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Stream Snapshot Creation

```
GET /api/database/snapshots/sse/create/{ticket}
Accept: text/event-stream
```

**Events:**
- `INFO` — Progress messages (pulling postgres image, running pg_dump)
- `PROGRESS` — Step completion updates
- `SUCCESS` — Snapshot created successfully (includes file size and metadata)
- `ERROR` — Failure details

### Active Snapshots

```
GET /api/database/snapshots/active
```

### Cancel Snapshot

```
POST /api/database/snapshots/cancel
Content-Type: application/json

{ "repository": "myapp", "sourceDatabaseName": "myapp_db" }
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `database.snapshot.storage.dir` | Directory for snapshot files | `data/snapshots/` |
| `database.snapshot.metadata.file` | JSON file for snapshot metadata | `data/snapshots-metadata.json` |
| `database.snapshot.max-size-mb` | Maximum snapshot size in MB | 1500 |
| `repository.pg-image.<repo>` | Postgres Docker image (use `none` for local tooling) | `postgres:latest` |

Snapshots also use the same PostgreSQL connection properties as dumps (`repository.pg-host.<repo>`, `repository.pg-port.<repo>`, etc.).

---

## UI

The snapshots tab is accessible on the Database page alongside the dumps tab. It provides:
- A **Create Snapshot** button with a modal for configuration
- A table with inline actions (download, delete, edit expiration)
- An active snapshots sidebar showing ongoing operations with cancel buttons
- Storage usage indicator
- Bulk selection with checkboxes for mass delete
