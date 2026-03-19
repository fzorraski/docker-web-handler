# Database Migration API Integration

## Overview

Docker Web Handler supports running SQL migration scripts against a database after a restore operation completes. When using **API mode**, the application calls an external HTTP endpoint to fetch the migration SQL based on a source and target version.

This document describes the expected API contract that external migration services must implement.

---

## Flow

The migration step runs **after** the restore and post-restore scripts complete:

```
Validate → Prepare → Create DB → Restore → Post-Restore Scripts → Running Migration
```

When the user selects API mode, they provide a `sourceVersion` and `targetVersion`. Docker Web Handler calls the configured API URL, receives the migration SQL, and executes it against the target database via `psql`.

---

## Configuration

### Enable the feature

```properties
# application.properties
database.migration.enabled=true
```

Or via environment variable:

```
DATABASE_MIGRATION_ENABLED=true
```

### Configure the API URL

The URL supports `{sourceVersion}` and `{targetVersion}` placeholders, which are replaced at runtime with the values entered by the user.

**Global (all repositories):**

```properties
database.migration.api-url=https://api.example.com/migration?source={sourceVersion}&target={targetVersion}
```

```
DATABASE_MIGRATION_API_URL=https://api.example.com/migration?source={sourceVersion}&target={targetVersion}
```

**Per-repository override:**

```properties
repository.migration-api-url.myapp=http://192.168.0.110:86/api/helper/migration?source={sourceVersion}&target={targetVersion}
```

```
REPOSITORY_MIGRATION_API_URL_MYAPP=http://192.168.0.110:86/api/helper/migration?source={sourceVersion}&target={targetVersion}
```

Placeholders can be used anywhere in the URL (path segments, query parameters, etc.).

---

## API Contract

### Request

```
GET {configured-url}
```

The placeholders `{sourceVersion}` and `{targetVersion}` are replaced before the request is made.

**Example:**

```
GET http://192.168.0.110:86/api/helper/migration?source=20.73.2&target=20.85.0
```

### Response

- **HTTP Status:** `200 OK`
- **Content-Type:** `application/json`

The API must return one of the supported response formats described below.

---

## Response Formats

Three response formats are supported. The recommended format is the **JSON object**, which provides the richest metadata.

### Format 1: JSON Object (Recommended)

```json
{
  "statements": [
    "-- Version: 1.1.0",
    "ALTER TABLE users ADD COLUMN email VARCHAR(255);",
    "CREATE INDEX idx_users_email ON users (email);",
    "-- Version: 1.2.0",
    "ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(100);"
  ],
  "sourceVersion": "1.0.0",
  "targetVersion": "1.2.0",
  "totalStatements": 3,
  "versionsIncluded": ["1.1.0", "1.2.0"]
}
```

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `statements` | `string[]` | **Yes** | Ordered list of SQL statements and comments to execute. Each element is a single SQL statement or a comment line. |
| `sourceVersion` | `string` | No | The starting version. Displayed in the progress log during execution. |
| `targetVersion` | `string` | No | The target version. Displayed in the progress log during execution. |
| `totalStatements` | `integer` | No | Number of executable SQL statements (excluding comments). If omitted, the count is calculated automatically. |
| `versionsIncluded` | `string[]` | No | List of version numbers covered by this migration. Displayed in the progress log during execution. |

### Format 2: JSON Array

A plain array of SQL strings. No metadata is available.

```json
[
  "-- Version: 1.1.0",
  "ALTER TABLE users ADD COLUMN email VARCHAR(255);",
  "CREATE INDEX idx_users_email ON users (email);"
]
```

### Format 3: Plain SQL

Raw SQL text returned as a string. No metadata is available.

```sql
-- Version: 1.1.0
ALTER TABLE users ADD COLUMN email VARCHAR(255);
CREATE INDEX idx_users_email ON users (email);
```

---

## Statement Rules

- Each element in the `statements` array should be a **single SQL statement** or a **comment line**.
- Statements that do not end with `;` will have a semicolon appended automatically (except comment lines starting with `--`).
- Comment lines (starting with `--`) are preserved in the output and passed to `psql` as-is.
- Statements are executed in the order they appear in the array.
- The entire set of statements is written to a temporary `.sql` file and executed via `psql -f` in a single session.

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| API returns non-200 status | Migration step fails with error message showing the status code. |
| API returns empty body | Migration step fails with "empty response" error. |
| `statements` array is empty | Migration step fails with "no SQL statements" error. |
| API is unreachable | Migration step fails with connection error details. |
| SQL execution fails (psql exits non-zero) | Migration step fails with the exit code. The psql output is streamed to the progress log for debugging. |

---

## Example Implementation

A minimal API endpoint (Java/JAX-RS) that returns the recommended format:

```java
@GET
@Path("/migration")
@Produces(MediaType.APPLICATION_JSON)
public Response getMigration(@QueryParam("source") String source,
                              @QueryParam("target") String target) {
    List<String> statements = migrationRepository.getStatementsBetween(source, target);
    List<String> versions = migrationRepository.getVersionsBetween(source, target);

    long executableCount = statements.stream()
            .filter(s -> !s.trim().startsWith("--"))
            .count();

    Map<String, Object> response = Map.of(
            "sourceVersion", source,
            "targetVersion", target,
            "statements", statements,
            "totalStatements", executableCount,
            "versionsIncluded", versions
    );

    return Response.ok(response).build();
}
```

---

## UI Behavior

- When `database.migration.enabled=true`, a **"Run Migration"** toggle appears in the Restore Dump and New Container (restore mode) modals.
- Enabling the toggle opens a configuration dialog with two modes:
  - **Manual:** Upload a `.sql` file or paste SQL directly.
  - **API:** Enter source and target version numbers. This mode is only available when a migration API URL is configured for the selected repository.
- During execution, the progress log shows:
  - Version range (if `sourceVersion`/`targetVersion` are provided)
  - Statement count
  - Versions included (if `versionsIncluded` is provided)
  - All `psql` output lines in real time
