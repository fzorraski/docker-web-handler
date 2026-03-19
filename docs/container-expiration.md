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

This prevents accidental data loss when multiple containers share the same database.

---

## API Endpoints

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
  "expiresAt": "2026-03-19T18:00:00"
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

- The containers table shows the expiration time in the **Expiration** column
- Action buttons per container: **Extend**, **Cancel Expiration**, **Cancel DB Deletion**
- Expiration time is formatted relative to the current time (e.g., "expires in 2h 30m")
