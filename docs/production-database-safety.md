# Production Database Safety Guide

## Overview

This guide covers the risks and recommended configuration when running Docker Web Handler in a production environment, particularly if your goal is **read-only database monitoring** (viewing statistics, sizes, connections, idle time) without exposing destructive operations.

---

## Destructive Database Operations

The application includes several endpoints that can **permanently drop PostgreSQL databases**:

| Operation | Endpoint | Effect |
|-----------|----------|--------|
| Delete single database | `DELETE /api/database/managed/{repo}/{dbName}` | Executes `DROP DATABASE` after terminating active connections |
| Bulk delete | `DELETE /api/database/managed/{repo}/bulk` | Drops up to 100 selected databases at once |
| Cleanup by idle time | `POST /api/database/managed/{repo}/cleanup-idle` | Drops all databases idle for more than N days |
| Auto-delete on expiration | (background, triggered by container expiration) | Drops the database associated with an expired container |
| Delete dumps/snapshots | `DELETE /api/database/dumps/delete/{id}`, `DELETE /api/database/snapshots/delete/{id}` | Removes dump/snapshot files from disk |

### Non-Destructive but Sensitive Operations

| Operation | Endpoint | Effect |
|-----------|----------|--------|
| Reset query stats | `POST /api/database/managed/{repo}/{db}/reset-query-stats` | Clears `pg_stat_statements` counters for the database |
| Reset table stats | `POST /api/database/managed/{repo}/{db}/reset-table-stats` | Clears table/index counters (`pg_stat_reset`) for the database |
| Reset single table stats | `POST /api/database/managed/{repo}/{db}/reset-table-stats/{schema}/{table}` | Clears counters for one table |
| Write queries | `POST /api/database/managed/{repo}/{db}/query` | Execute arbitrary SQL when `database.query.write-enabled=true` |

Stats resets are **non-destructive** (no data loss) but reset accumulated performance counters that may have been building for months. Write queries can modify data directly.

All of these operations are **irreversible** — there is no undo or recycle bin.

---

## Existing Safeguards

### Operations Password

All destructive operations require an operations password via the `X-Dump-Password` header or request body. This is enforced when `database.dump.operations-password.required=true` (default).

### Protected Flag

Individual databases can be marked as "protected", which prevents them from being deleted by any method (single, bulk, or cleanup). Protection can only be toggled with the operations password. Protection and ownership are scoped to the PostgreSQL server, not to the repository tab: repositories whose `pg-host` and `pg-port` match share one metadata record per database, so a database protected in one tab cannot be dropped from a sibling tab (see [managed-databases.md](managed-databases.md#repositories-sharing-a-postgresql-server)).

### Active Connection Checks

- **Single delete:** warns if the database has active connections (can be forced with `?force=true`)
- **Bulk delete and cleanup:** silently skip databases with active connections

### Container Usage Detection

Deletion is blocked if any tracked container is currently using the database.

### Stats Reset Password

All statistics reset operations require the operations password. Resets are disabled by default (`database.query-stats.reset-enabled=false`).

### Query Runner Safeguards

The query runner is disabled by default (`database.query.enabled=false`). When enabled, it defaults to read-only mode. Write queries require an additional opt-in (`database.query.write-enabled=true`). All queries have a configurable execution timeout (default 30 seconds).

---

## Recommended Configuration for Production

### Option 1: Read-Only Statistics (Safest)

If you only need to **view** database metrics (size, connections, idle time), disable all destructive features:

```properties
# Enable viewing databases (read-only metrics)
database.managed.enabled=true

# Disable all destructive features
database.dump.enabled=false
database.deletion-on-expiration.enabled=false
database.query-stats.reset-enabled=false
database.query.write-enabled=false

# Set a strong operations password as a safety net
database.dump.operations-password=<strong-random-password>
database.dump.operations-password.required=true
```

With this configuration:
- The Databases tab shows live metrics (size, connections, idle time)
- Database insights (health, activity, table stats, index analysis) are fully available
- Delete buttons are visible in the UI but require the operations password
- Statistics reset buttons are hidden
- Query runner is read-only (if enabled)
- Dump upload/restore/delete endpoints are disabled entirely
- Databases are never auto-deleted when containers expire

> **Note:** `database.managed.enabled=true` still exposes the delete API endpoints. The operations password is the barrier. If you need to fully eliminate the delete endpoints, set `database.managed.enabled=false`, but this also disables the statistics UI.

### Option 2: Fully Disabled (No Database Features)

If you want zero database functionality:

```properties
database.managed.enabled=false
database.dump.enabled=false
database.deletion-on-expiration.enabled=false
```

The Database page will not appear in the UI, and all database-related endpoints return errors.

### Option 3: Full Features with Safeguards

If you need both statistics and occasional database management:

```properties
database.managed.enabled=true
database.dump.enabled=true
database.deletion-on-expiration.enabled=false

# Strong passwords for both tiers
database.dump.upload-password=<strong-password-1>
database.dump.upload-password.required=true
database.dump.operations-password=<strong-password-2>
database.dump.operations-password.required=true
```

Additionally:
- Mark all critical production databases as **protected** via the UI or API
- Keep `database.deletion-on-expiration.enabled=false` to prevent automatic drops
- Share the operations password only with authorized personnel

---

## Configuration Reference

| Property | Description | Default | Risk Impact |
|----------|-------------|---------|-------------|
| `database.managed.enabled` | Show Databases tab with live metrics and delete operations | `true` | Exposes delete endpoints |
| `database.dump.enabled` | Enable dump upload, restore, and delete | `false` | Allows restoring over existing databases |
| `database.deletion-on-expiration.enabled` | Auto-drop database when its container expires | `false` | Silent, automatic data loss |
| `database.dump.operations-password` | Password for delete, cleanup, protection toggle, and stats reset | (empty) | Empty = anyone can delete |
| `database.dump.operations-password.required` | Enforce password on destructive operations | `true` | `false` = no password needed |
| `database.dump.upload-password` | Password for dump/snapshot uploads | (empty) | Controls who can upload |
| `database.dump.upload-password.required` | Enforce upload password | `false` | `false` = open uploads |
| `database.query-stats.reset-enabled` | Allow resetting PostgreSQL statistics counters | `false` | Loses accumulated performance data |
| `database.query.write-enabled` | Allow INSERT/UPDATE/DELETE via query runner | `false` | Direct data modification |

All properties can be overridden via environment variables (e.g., `DATABASE_MANAGED_ENABLED=false`).

---

## Risk Summary

| Scenario | Risk Level | Mitigation |
|----------|------------|------------|
| `database.managed.enabled=true`, no password set | **Critical** | Set `database.dump.operations-password` immediately |
| `database.deletion-on-expiration.enabled=true` | **High** | Disable unless you explicitly want auto-drops |
| `database.dump.operations-password.required=false` | **High** | Always keep this `true` in production |
| `database.query.write-enabled=true` | **High** | Keep `false` in production unless explicitly needed |
| Cleanup with low idle threshold (1-7 days) | **High** | Use thresholds of 30+ days; review the preview before confirming |
| `database.query-stats.reset-enabled=true` | **Medium** | Loses accumulated stats; requires password but data is non-recoverable |
| `database.managed.enabled=true`, strong password set | **Low** | Password prevents unauthorized deletion |
| `database.managed.enabled=false` | **None** | All database endpoints are disabled |
