# Post-Restore Scripts

## Overview

Post-restore scripts are SQL files that run automatically after a database dump or snapshot is restored. They allow you to apply environment-specific fixes, reset passwords, update URLs, disable integrations, or perform any other adjustments needed for the restored database to work in a development environment.

Scripts are organized into two categories: **mandatory** (always run) and **optional** (user-selectable).

---

## Script Types

### Mandatory Scripts

Stored in the directory configured by `repository.post-restore-mandatory-dir.<repo>`. These scripts run automatically after every restore — the user cannot skip them.

Use mandatory scripts for:
- Resetting admin passwords to dev defaults
- Updating API URLs to point to dev services
- Disabling email/SMS/webhook integrations
- Granting dev-specific database permissions

### Optional Scripts

Stored in the directory configured by `repository.post-restore-optional-dir.<repo>`. These scripts are presented to the user as checkboxes during the restore flow — the user selects which ones to run.

Use optional scripts for:
- Inserting test data
- Enabling debug features
- Running specific data transformations
- Applying feature-specific configuration

---

## Execution Order

```
Restore Dump/Snapshot
    → Mandatory Scripts (all, in alphabetical filename order)
    → Optional Scripts (selected, in alphabetical filename order)
    → Migration (if configured)
```

Scripts are executed via `psql -v ON_ERROR_STOP=1 -f` against the target
database: the first SQL error ends the script with exit code 3, and psql's
`-f` mode does not run a transaction of its own. Wrap a script in
`BEGIN; ... COMMIT;` when it must apply atomically; on an error the open
transaction is rolled back when psql exits. Statements that cannot run inside
a transaction block (`VACUUM`, `CREATE INDEX CONCURRENTLY`) work as long as the
script does not wrap them.

---

## Failure Handling

A failing **mandatory** script always aborts the restore: no further script or
migration runs, and the target database is dropped (see below). Mandatory
scripts exist to strip production configuration out of the restored data, so
"always run" means "must succeed".

For **optional** scripts the behavior is controlled by `post-restore-scripts.on-failure`:

| Value | Behavior |
|-------|----------|
| `stop` | Abort the entire restore operation. No further scripts or migration will run. |
| `continue` | Log the error and continue with the next script. |

The value is validated at startup (case and surrounding whitespace are
ignored). Any other value makes the application refuse to start, because an
unrecognized setting used to let a failed script pass as success.

### What failed, not only the exit code

The failure event and the audit `reason` carry the PostgreSQL error lines the
script printed (the first five, plus a count), e.g.
`Script '02-master-ip-and-port-cleaner.sql' failed with exit code 3. PostgreSQL reported 1 error(s): psql:/scripts/mandatory/02-master-ip-and-port-cleaner.sql:4: ERROR:  relation "master_config" does not exist`.
The same line is written to the server log at `ERROR` level. A script is a
failure when psql exits non-zero **or** when any `ERROR:` / `FATAL:` /
`PANIC:` line was printed, whatever the exit code, so an SQL error can never
pass as success. The restore command itself (`psql` / `pg_restore`) gets the
same error detail in its failure message.

### No database is left behind

Once the dump has landed in the target database, the data it holds is a raw
copy of the source: integrations, endpoints, credentials and licenses are still
the production ones until the mandatory scripts sanitize them. A restore that
stops before that point (a failing script, a restore error, a cancellation)
therefore **drops the target database**, whether it was created by that run or
was an existing database being overwritten (its previous contents were already
destroyed by the restore). This keeps unsanitized copies out of the
"existing database" list, where no script ever runs.

If the drop itself fails, an `ERROR` event and a server log line flag the
database as containing **UNSANITIZED** data so it can be removed by hand.

Every outcome is written to the audit trail:

| Action | When | Detail |
|--------|------|--------|
| `DATABASE_RESTORE` | Restore, scripts and migration all completed | `source=<dump>` |
| `DATABASE_RESTORE_FAILED` | Any step failed | `source=…; repository=…; database=dropped|drop-failed|untouched; reason=<last error>` |
| `DATABASE_RESTORE_CANCELLED` | The user cancelled | same as above |

`database=untouched` means the failure happened before anything was written
(for example while decompressing the dump), so nothing was dropped.
`database=kept` means the scripts had already completed and only the optional
migration failed: the data is sanitized, so the database stays for a retry.

---

## API Endpoints

### Get Post-Restore Scripts

```
GET /api/database/dumps/post-restore-scripts?repository=myapp
```

**Response:**

```json
{
  "enabled": true,
  "mandatory": [
    "01-reset-passwords.sql",
    "02-update-urls.sql"
  ],
  "optional": [
    "insert-test-data.sql",
    "enable-debug-logging.sql",
    "setup-feature-flags.sql"
  ],
  "onFailure": "stop"
}
```

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `post-restore-scripts.enabled` | Enable post-restore scripts | false |
| `post-restore-scripts.on-failure` | Behavior when an **optional** script fails: `stop` or `continue`; mandatory failures always stop | `stop` |
| `repository.post-restore-mandatory-dir.<repo>` | Directory containing mandatory SQL scripts | — |
| `repository.post-restore-optional-dir.<repo>` | Directory containing optional SQL scripts | — |

### Example Configuration

```properties
post-restore-scripts.enabled=true
post-restore-scripts.on-failure=continue

repository.post-restore-mandatory-dir.myapp=/opt/scripts/myapp/mandatory
repository.post-restore-optional-dir.myapp=/opt/scripts/myapp/optional
```

---

## UI

When restoring a dump (either standalone or during container creation):

1. If post-restore scripts are enabled and the selected repository has scripts configured, a **Scripts** section appears
2. Mandatory scripts are listed with a lock icon — they will always run
3. Optional scripts are listed as checkboxes — select the ones you want to run
4. During execution, the progress log shows each script being executed and its output
