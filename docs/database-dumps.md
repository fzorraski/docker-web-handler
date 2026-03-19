# Database Dumps

## Overview

Docker Web Handler allows you to upload, store, and restore PostgreSQL database dumps through the web interface. Dumps can be restored into any configured PostgreSQL database — either as part of container creation or as a standalone operation.

---

## Features

### Upload Dumps

Upload PostgreSQL dump files for later restoration:

- **Supported formats:** `.sql`, `.dump`, `.gz` (gzip-compressed)
- **Maximum size:** Configurable via `database.dump.max-size-mb` (default: 1500 MB)
- **Upload password:** Required for uploading (configured via `database.dump.upload-password`)
- **Optional metadata:** Database name, version, description, expiration date
- **Progress bar:** XHR-based upload with real-time progress indication

### List & Search Dumps

All uploaded dumps are displayed in a table with:

| Column | Description |
|--------|-------------|
| Filename | Original uploaded filename |
| Database | Associated database name |
| Version | Version label |
| Uploaded | Upload timestamp |
| Expiration | Auto-deletion date (if set) |
| Size | File size (formatted) |
| Last Used | Last time the dump was restored |
| Actions | Download, Restore, Edit, Delete |

### Download Dumps

Download any stored dump file. If the original file is not gzip-compressed, it is automatically compressed during download to reduce transfer time.

### Edit Metadata

Update a dump's version or database name after upload. Requires the operations password.

### Edit Expiration

Set, change, or remove the auto-deletion date for a dump. Expired dumps are automatically cleaned up. Requires the operations password.

### Delete Dumps

- **Single delete:** Remove one dump by ID
- **Bulk delete:** Select multiple dumps via checkboxes and delete them all at once
- **Cleanup idle:** Automatically delete dumps that haven't been used (restored) for a specified number of days

All delete operations require the operations password.

### Restore Dumps

Restore a dump into a target PostgreSQL database. The restore flow:

```
Validate → Pull Postgres Image → Create Database (optional) → Restore Dump
    → Run Mandatory Post-Restore Scripts → Run Optional Scripts → Run Migration (optional)
```

- **Repository selector:** Choose which repository's database configuration to use
- **Target database:** Select from existing databases or enter a new name
- **Create database:** Option to create the database if it doesn't exist
- **Post-restore scripts:** Run mandatory and optionally selected SQL scripts after restore
- **Migration:** Optionally apply database migrations after restore (manual SQL or API mode)
- **Cancellable:** Ongoing restores can be cancelled via the active restores panel

### Storage Info

Displays current storage usage:
- Total bytes used
- Number of files stored
- Maximum allowed storage

---

## API Endpoints

### Check Feature Enabled

```
GET /api/database/dumps/enabled
```

**Response:** `true` or `false`

### List Dumps

```
GET /api/database/dumps/list
```

**Response:** Array of `DatabaseDump` objects.

### Upload Dump

```
POST /api/database/dumps/upload
Content-Type: multipart/form-data

Fields: file (required), password, databaseName, version, description, expiresAt
```

**Response:** `DatabaseDump` object on success.

### Download Dump

```
GET /api/database/dumps/download/{id}
```

**Response:** File download (gzip-compressed).

### Delete Dump

```
DELETE /api/database/dumps/delete/{id}
X-Dump-Password: operations-password
```

### Bulk Delete

```
DELETE /api/database/dumps/delete/bulk
X-Dump-Password: operations-password
Content-Type: application/json

["uuid-1", "uuid-2", "uuid-3"]
```

### Update Metadata

```
PUT /api/database/dumps/metadata/{id}
X-Dump-Password: operations-password
Content-Type: application/json

{ "version": "1.2.0", "databaseName": "myapp_db" }
```

### Update Expiration

```
PUT /api/database/dumps/expiration/{id}
X-Dump-Password: operations-password
Content-Type: application/json

{ "expiresAt": "2026-04-19T00:00:00" }
```

Set `expiresAt` to `null` to remove the expiration.

### Cleanup Idle

```
POST /api/database/dumps/cleanup-idle
Content-Type: application/json

{ "password": "operations-password", "minDays": 30 }
```

**Response:**

```json
{ "success": true, "deleted": 5 }
```

### Storage Info

```
GET /api/database/dumps/storage-info
```

**Response:**

```json
{
  "totalBytes": 1073741824,
  "fileCount": 12,
  "maxBytes": 1610612736
}
```

### Prepare Restore

```
POST /api/database/dumps/sse/restore/prepare
Content-Type: application/json

{
  "dumpId": "uuid",
  "repository": "myapp",
  "targetDatabase": "myapp_db",
  "createDatabase": true,
  "password": "operations-password",
  "selectedOptionalScripts": ["cleanup.sql"],
  "migrationMode": "API",
  "migrationSourceVersion": "1.0.0",
  "migrationTargetVersion": "1.2.0"
}
```

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Stream Restore

```
GET /api/database/dumps/sse/restore/{ticket}
Accept: text/event-stream
```

### Active Restores

```
GET /api/database/dumps/restore/active
```

### Cancel Restore

```
POST /api/database/dumps/restore/cancel
Content-Type: application/json

{ "repository": "myapp", "targetDatabase": "myapp_db" }
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `database.dump.enabled` | Enable the dump feature | false |
| `database.dump.upload-password` | Password for uploading dumps | — |
| `database.dump.operations-password` | Password for restore/delete operations | — |
| `database.dump.storage.dir` | Storage directory for dump files | `data/dumps/` |
| `database.dump.max-size-mb` | Maximum file size in MB | 1500 |
| `database.dump.metadata.file` | JSON file for dump metadata | `data/dumps-metadata.json` |

---

## UI

The dumps tab is accessible at the Database page. It provides:
- An upload button with a modal for file selection and metadata
- A table with inline actions for each dump
- An active restores sidebar showing ongoing operations with cancel buttons
- Storage usage indicator
- Bulk selection with checkboxes for mass delete
