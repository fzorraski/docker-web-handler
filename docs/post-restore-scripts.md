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

Scripts are executed via `psql -f` against the target database.

---

## Failure Handling

The behavior when a script fails is controlled by `post-restore-scripts.on-failure`:

| Value | Behavior |
|-------|----------|
| `stop` | Abort the entire restore operation. No further scripts or migration will run. |
| `continue` | Log the error and continue with the next script. |

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
| `post-restore-scripts.on-failure` | Behavior on failure: `stop` or `continue` | `stop` |
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
