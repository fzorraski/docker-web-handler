# Container Expiration

## Overview

Container expiration allows you to schedule automatic cleanup of containers after a defined period. When a container expires, it is stopped and removed. Optionally, the associated PostgreSQL database can also be deleted on expiration.

Expiration state is persisted to a JSON file so it survives application restarts.

---

## How It Works

1. When a container is created, an expiration time is set (default: 8 hours from creation)
2. A background scheduler monitors all active expirations
3. When the expiration time is reached, the container is stopped and removed
4. If `deleteDatabaseOnExpiration` was enabled, the associated database is also dropped

---

## Actions

### Edit Expiration

Change the expiration time of an existing container, or add an expiration to a container that was created without one.

- **Click the countdown chip** in the Expires column to open the edit dialog
- **Click "Add expiration"** for containers without expiration
- Also available from the **action menu** (three-dot menu)
- Supports the same time presets as container creation: 2 Hours, 1 Day, 3 Days, 1 Week
- Supports enabling/disabling **Delete database on expiration** with the same validation rules as creation (conflict detection, protected database checks)
- When enabling database deletion after creation, a **confirmation dialog** requires typing the database name (same flow as during creation)
- Operations password is validated before the confirmation dialog opens

### Extend Expiration

Adds additional time to a container's current expiration. Useful when you need the container for longer than originally planned.

- Default extension: 10 minutes
- Maximum single extension: 1440 minutes (24 hours)
- Can be applied multiple times

### Cancel Expiration

Removes the scheduled expiration entirely. The container will run indefinitely until manually stopped or removed.

### Cancel Database Deletion

Keeps the expiration active but disables the database deletion that would occur when the container expires. The container will still be stopped and removed, but the database will be preserved.

---

## Database Conflict Detection

Before restoring a dump or creating a container with a database, the system checks for conflicts:

- If another container is scheduled to delete that database on expiration, a warning is shown
- The conflict response includes which container owns the deletion schedule and when it expires

When editing expiration on an existing container, the current container is excluded from the conflict list (so a container using its own database is not flagged as a conflict).

This prevents accidental data loss when multiple containers share the same database.

---

## API Endpoints

### Update Expiration

Set or change the expiration time and database deletion flag for a container. Works for containers with or without an existing expiration record.

```
POST /api/containers/update-expiration
Content-Type: application/json

{
  "containerId": "abc123...",
  "expiresAt": "2026-04-21T18:00:00",
  "deleteDatabaseOnExpiration": false,
  "operationsPassword": "password"
}
```

- `expiresAt`: ISO local datetime string, or `null` to remove expiration
- `deleteDatabaseOnExpiration`: requires `expiresAt` to be set, operations password, and `database.deletion-on-expiration.enabled=true`
- `operationsPassword`: only required when enabling database deletion

**Response:**
```json
{ "success": true }
```

**Error responses:** 400 (validation), 403 (invalid password), 404 (container not found)

### Extend Expiration

```
POST /api/containers/extend-expiration?minutes=30
Content-Type: application/json

{ "containerId": "abc123..." }
```

**Response:** `true` on success.

### Cancel Expiration

```
POST /api/containers/cancel-expiration
Content-Type: application/json

{ "containerId": "abc123..." }
```

**Response:** `true` on success.

### Cancel Database Deletion

```
POST /api/containers/cancel-db-deletion
Content-Type: application/json

{ "containerId": "abc123..." }
```

**Response:** `true` on success.

### Check Database Conflicts

```
GET /api/containers/database-conflicts?databaseName=myapp_db
```

**Response:**

```json
{
  "scheduledForDeletionBy": "container-name",
  "inUseByContainers": ["container-a", "container-b"],
  "expiresAt": "2026-03-19T18:00:00",
  "protectedFlag": false
}
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `container.default-expiration-minutes` | Default expiration for new containers | 480 (8 hours) |
| `expiration.storage.file` | JSON file for expiration persistence | `data/expirations.json` |
| `database.deletion-on-expiration.enabled` | Allow database deletion on expiration | false |

---

## UI

- The containers table shows the expiration time in the **Expires** column as a live countdown chip
- **Click the countdown chip** to edit the expiration time or database deletion settings
- Containers without expiration show an **"Add expiration"** chip
- The **action menu** includes an "Edit expiration" / "Add expiration" option
- Action buttons per container: **Extend (+10 min)**, **Cancel Expiration** (X on chip), **Cancel DB Deletion** (X on warning chip)
- Database deletion warning chip shows **"DB will be deleted"** with tooltip showing the full database name
