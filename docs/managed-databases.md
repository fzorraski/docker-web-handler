# Managed Databases

## Overview

The Managed Databases feature provides a UI for viewing and managing live PostgreSQL databases directly from the Database page. It connects to configured PostgreSQL servers, displays real-time metrics (size, active connections, idle time), and allows dropping databases, bulk deletion, cleanup by idle time, and protection of critical databases from accidental deletion.

---

## Features

### List Databases

All PostgreSQL databases (excluding `postgres` and templates) are displayed in a table with live metrics:

| Column | Description |
|--------|-------------|
| Database Name | Name of the PostgreSQL database |
| Size | Database size from `pg_database_size()` with a relative usage bar |
| Active Connections | Number of current connections from `pg_stat_activity` |
| Idle Since | Combined idle time (see [How Idle Tracking Works](#how-idle-tracking-works)) |
| Protected | Whether the database is protected from deletion |
| Actions | Delete button (disabled for protected databases) |

When multiple repositories are configured, each gets its own sub-tab.

### Filters

- **Text search:** Filter databases by name
- **With connections:** Toggle to show only databases with active connections
- **Idle since:** Dropdown to filter by idle time (> 7 days, > 14 days, > 30 days, > 60 days, Never used)

### Protection Flag

Mark databases as "protected" to prevent them from being deleted — either individually or via cleanup. Toggling protection requires the operations password. Protected databases are:

- Excluded from single and bulk delete (server returns 409 Conflict)
- Excluded from idle cleanup operations
- Visually marked with a shield icon in the UI

### Delete Databases

- **Single delete:** Drop one database (requires operations password, rejects if protected)
- **Bulk delete:** Select multiple databases via checkboxes and drop all at once (protected databases are skipped)
- **Right-click context menu:** Toggle protection or delete individual databases

All delete operations terminate active connections before dropping (`pg_terminate_backend`).

### Cleanup by Idle Time

Automatically drop databases that have been idle for more than a specified number of days. The cleanup dialog shows:

- A slider to set the minimum idle time (1-90 days)
- A live preview of which databases will be affected
- Protected databases are always skipped

### Summary Card

Displays total database count, total size across all databases, and number of protected databases.

### Database Description

Add a description to any database for identification purposes. Click the description field in the database row to edit it. Descriptions are stored in the metadata file and persist across restarts.

---

## Database Insights

Click a database name to open the insights dialog with detailed health, activity, and performance data.

### Health Overview

| Metric | Description |
|--------|-------------|
| Size | Database size from `pg_database_size()` |
| Cache Hit Ratio | Buffer cache effectiveness (`hits / (hits + reads) * 100`) |
| Active Connections | Current active sessions |
| Waiting Connections | Sessions waiting for locks |
| Long-Running Queries | Queries running longer than expected |
| Dead Tuples | Rows marked for deletion but not yet vacuumed |
| Transaction ID Age | Age of `datfrozenxid` — high values indicate vacuum needs |
| Commits / Rollbacks | Cumulative transaction counts |
| Temp Bytes / Files | Temporary file usage indicating memory pressure |

### Activity Monitoring

- **Active Sessions** (top 20): User, state, query, client address, duration, and wait type
- **Top Users by Connection Count**: Breakdown of active, idle, and total connections per user
- **Blocked Processes** (top 20): Deadlock detection showing blocking/blocked PIDs with wait duration

### Top Queries (pg_stat_statements)

Requires the `pg_stat_statements` extension. If not installed, the UI provides a one-click button to enable it (runs `CREATE EXTENSION IF NOT EXISTS pg_stat_statements`).

| Column | Description |
|--------|-------------|
| Query | Normalized query text |
| Calls | Total execution count |
| Total Time | Cumulative execution time |
| Mean Time | Average execution time per call |
| Rows | Total rows returned |
| Temp Files | Temp file I/O (indicates queries spilling to disk) |

### Temp File Queries

A dedicated view showing queries that generate temporary files, sorted by temp file size. These queries exceed `work_mem` and write intermediate results to disk, which is significantly slower than in-memory processing.

### Table & Index Statistics

| Column | Description |
|--------|-------------|
| Table Name | Schema-qualified table name |
| Total Size | Combined data + index size |
| Data Size | Table data only |
| Index Size | All indexes on the table |
| Live / Dead Tuples | Current row estimates |
| Seq Scans | Sequential scan count |
| Index Scans | Index scan count |
| IDX Usage | `idx_scan / (seq_scan + idx_scan) * 100` — low values indicate missing indexes |
| Last Vacuum/Analyze | Timestamps for manual and auto vacuum/analyze |

**Unused Indexes:** Indexes with `idx_scan = 0` (excluding primary keys and unique constraints) are listed separately with wasted disk space. See [Database Index Analysis](database-index-analysis.md) for interpretation guidance.

**Index Impact:** Per-table analysis showing unused index count, wasted space, total writes, and estimated write overhead (~2-5% per unused index).

### SQL Query Runner

When `database.query.enabled=true`, a query runner is available in the insights dialog:

- **Read-only mode** (default): Only SELECT queries are allowed
- **Write mode** (`database.query.write-enabled=true`): INSERT, UPDATE, DELETE also allowed
- **EXPLAIN / ANALYZE**: Run EXPLAIN on any query to see the execution plan
- **Pagination**: Results paginated with configurable max page size (default 500 rows)
- **Timeout**: Query execution timeout protection (default 30 seconds)
- **Row count caching**: Total row count from the first page is reused on subsequent pages

### Statistics Reset

When `database.query-stats.reset-enabled=true`, reset buttons appear in the insights dialog (all require operations password):

- **Reset Query Stats** (Top Queries tab): Calls `pg_stat_statements_reset()` scoped to the current database
- **Reset Table Stats** (Tables tab): Calls `pg_stat_reset()` for the current database
- **Reset Single Table Stats** (per-row button): Calls `pg_stat_reset_single_table_counters()` for one table

All resets are scoped to the current database — other databases on the same server are not affected. The `statsResetAt` date shown in the UI indicates when the counters started accumulating.

### HTML Reports

Generate downloadable self-contained HTML reports from the insights dialog. Reports include:

- Health metrics (size, cache hit ratio, connections, dead tuples)
- Activity (sessions, blocked processes, top queries, users)
- Table statistics (sizes, vacuum status, index usage)
- Temp file queries (memory pressure indicators)
- Server health (PostgreSQL version, uptime, connection limits)

---

## How Idle Tracking Works

The "Idle Since" value comes from **two sources combined**:

### 1. PostgreSQL Stats (real-time)

The backend queries `pg_stat_activity` for each database and takes the most recent timestamp from `state_change`, `query_start`, and `xact_start`. This captures SQL activity from **any client** — not just this application.

**Limitation:** `pg_stat_activity` only tracks **currently open connections**. Once all connections to a database close, PostgreSQL has no activity data to report.

### 2. App-Level Tracking (persistent)

A JSON file (`data/managed-databases.json`) stores an `appLastUsedAt` timestamp per database. This is updated when:

- A **dump is restored** into the database
- A **snapshot is taken** from the database

This persists across restarts, so even after connections close, the application remembers when a database was last used through its own operations.

### Combined Result

The backend computes: `effectiveLastUsedAt = max(pgLastActivity, appLastUsedAt)` — whichever is more recent wins.

- If both are null, the database shows **"Never used"**
- "Never used" databases are always considered eligible for idle cleanup

### Data Refresh

The frontend fetches live data from PostgreSQL **on demand**:

- When the Databases tab is opened
- Every 30 seconds while the tab remains open

There is no background polling on the backend. If nobody has the tab open, the only updates are app-level tracking from restore/snapshot operations.

---

## API Endpoints

### Check if Enabled

```
GET /api/database/managed/enabled
```

**Response:** `true` or `false`

### List Repositories

```
GET /api/database/managed/repositories
```

**Response:** Array of repository names with PG configuration.

### List Databases

```
GET /api/database/managed/list/{repository}
```

**Response:** Array of `ManagedDatabaseInfo` objects:

```json
[
  {
    "name": "myapp_db",
    "repository": "myapp",
    "sizeBytes": 1073741824,
    "activeConnections": 3,
    "pgLastActivity": "2026-04-17T10:30:00Z",
    "appLastUsedAt": "2026-04-15T08:00:00Z",
    "effectiveLastUsedAt": "2026-04-17T10:30:00Z",
    "protectedFlag": false,
    "createdAt": "2026-04-10T12:00:00Z"
  }
]
```

### Delete Database

```
DELETE /api/database/managed/{repository}/{databaseName}
X-Dump-Password: operations-password
```

Returns 409 if the database is protected.

### Bulk Delete

```
DELETE /api/database/managed/{repository}/bulk
X-Dump-Password: operations-password
Content-Type: application/json

["db_name_1", "db_name_2"]
```

**Response:**

```json
{ "success": true, "deleted": 2, "skipped": 1 }
```

Protected databases and invalid names are skipped (counted in `skipped`).

### Toggle Protected

```
PUT /api/database/managed/{repository}/{databaseName}/protected
X-Dump-Password: operations-password
```

**Response:**

```json
{ "success": true, "protected": true }
```

### Cleanup Idle Databases

```
POST /api/database/managed/{repository}/cleanup-idle
Content-Type: application/json

{ "password": "operations-password", "minDays": 30 }
```

**Response:**

```json
{ "success": true, "deleted": 5 }
```

### Update Description

```
PUT /api/database/managed/{repository}/{databaseName}/description
Content-Type: application/json

{ "description": "Main application database" }
```

### Database Insights Endpoints

```
GET /api/database/managed/activity/{repository}/{databaseName}    - Activity details (sessions, users, blocked)
GET /api/database/managed/health/{repository}/{databaseName}      - Health metrics
GET /api/database/managed/health/{repository}                     - Server-wide health
GET /api/database/managed/tables/{repository}/{databaseName}      - Table & index statistics
GET /api/database/managed/queries/{repository}/{databaseName}/{tableName}  - Top queries for table
GET /api/database/managed/temp-queries/{repository}/{databaseName} - Temp file queries
GET /api/database/managed/details/{repository}/{databaseName}     - Detailed database info
GET /api/database/managed/{repository}/{databaseName}/report      - Download HTML insights report
```

### pg_stat_statements & Stats Reset

```
POST /api/database/managed/{repository}/{databaseName}/enable-pgss  - Enable pg_stat_statements extension
POST /api/database/managed/{repository}/{databaseName}/reset-query-stats  - Reset query statistics
POST /api/database/managed/{repository}/{databaseName}/reset-table-stats  - Reset all table stats
POST /api/database/managed/{repository}/{databaseName}/reset-table-stats/{schemaName}/{tableName}  - Reset single table
```

All reset endpoints require the operations password via `X-Dump-Password` header.

### Query Runner Endpoints

```
POST /api/database/managed/{repository}/{databaseName}/query    - Execute SQL query
POST /api/database/managed/{repository}/{databaseName}/explain  - Run EXPLAIN on query
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `database.managed.enabled` | Enable the Managed Databases tab | `false` |
| `database.managed.metadata.file` | JSON file for per-database metadata (protected flags, app-level tracking) | `data/managed-databases.json` |
| `database.managed.cache.ttl-seconds` | Cache TTL for database queries (shared by all users) | `30` |
| `database.query.enabled` | Enable SQL query runner in insights dialog | `false` |
| `database.query.write-enabled` | Allow write queries (INSERT/UPDATE/DELETE) | `false` |
| `database.query.timeout-seconds` | Query execution timeout | `30` |
| `database.query.max-page-size` | Maximum rows per result page | `500` |
| `database.query.cache-total-rows` | Reuse row count from first page on subsequent pages | `true` |
| `database.query-stats.reset-enabled` | Enable statistics reset buttons in insights | `false` |

The feature also requires per-repository PostgreSQL connection settings:

| Property | Description | Default |
|----------|-------------|---------|
| `repository.pg-host.<repo>` | PostgreSQL host | (required) |
| `repository.pg-port.<repo>` | PostgreSQL port | `5432` |
| `repository.pg-user.<repo>` | PostgreSQL user | `postgres` |
| `repository.pg-password.<repo>` | PostgreSQL password | (empty) |

Environment variable overrides: `DATABASE_MANAGED_ENABLED`, `DATABASE_MANAGED_METADATA_FILE`, `DATABASE_MANAGED_CACHE_TTL_SECONDS`, `DATABASE_QUERY_ENABLED`, `DATABASE_QUERY_STATS_RESET_ENABLED`.

---

## UI

The Managed Databases tab is accessible on the Database page alongside the Dumps and Snapshots tabs (only visible when `database.managed.enabled=true`). It provides:

- **Repository sub-tabs** for each configured PostgreSQL connection
- A **summary card** with total count, total size, and protected count
- **Filter controls:** text search, connections toggle, idle-since dropdown
- A **sortable, paginated table** with live metrics
- **Right-click context menu** for quick actions
- **Bulk selection** with checkboxes for mass delete
- **Cleanup dialog** with idle time slider and live preview of affected databases
- **Password-gated operations** for all destructive actions
