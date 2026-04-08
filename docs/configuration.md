# Configuration Reference

## Overview

Docker Web Handler is configured through `application.properties` (or equivalent Quarkus config sources). All properties can be overridden via environment variables by converting the property name to uppercase and replacing dots and hyphens with underscores.

Example: `database.dump.enabled` → `DATABASE_DUMP_ENABLED`

---

## Docker Connection

| Property | Description | Default |
|----------|-------------|---------|
| `docker.host` | Docker socket path | `unix:///var/run/docker.sock` |
| `docker.connect-timeout` | Connection timeout (ms) | 30000 |
| `docker.response-timeout` | Response timeout (ms) | 45000 |

---

## Repository Management

### Allowed Repositories

```properties
allowed.run.repositories=myapp,postgres,redis
```

Comma-separated whitelist of Docker image repositories that users can run. Only these repositories appear in the "New Container" modal dropdown.

### Registry Credentials

**Global (all repositories):**

| Property | Description |
|----------|-------------|
| `docker.registry.url` | Registry URL (default: Docker Hub) |
| `docker.registry.username` | Registry username |
| `docker.registry.password` | Registry password |

**Per-repository override:**

| Property | Description |
|----------|-------------|
| `repository.registry-url.<repo>` | Registry URL for this repo |
| `repository.registry-username.<repo>` | Username for this repo |
| `repository.registry-password.<repo>` | Password for this repo |

Per-repository credentials support pipe-separated multi-registry configurations for repos hosted across multiple registries.

---

## Container Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `container.default-expiration-minutes` | Default expiration for new containers | 480 (8 hours) |
| `container.memory-limit.enabled` | Show memory limit field in UI | false |
| `repository.env-keys.<repo>` | Pre-configured environment variables (KEY=VALUE pairs) | — |
| `repository.hidden-env.<repo>` | Hidden env vars (sent to container, not shown in UI) | — |
| `repository.java-opts-var.<repo>` | Java opts env var name for auto-heap calculation | — |

### Example

```properties
container.default-expiration-minutes=480
container.memory-limit.enabled=true

repository.env-keys.myapp=DB_HOST=postgres,APP_ENV=dev
repository.hidden-env.myapp=SECRET_KEY=abc123
repository.java-opts-var.myapp=JAVA_OPTS
```

---

## Port Mapping

| Property | Description | Default |
|----------|-------------|---------|
| `container.port-mapping.enabled` | Enable automatic port mapping | false |
| `container.port-mapping.host-port-start` | Starting host port number | 10000 |
| `repository.container-ports.<repo>` | Comma-separated container ports to map | — |
| `repository.port-paths.<repo>` | Port:path pairs for hyperlink URLs | — |

### Example

```properties
container.port-mapping.enabled=true
container.port-mapping.host-port-start=10000

repository.container-ports.myapp=8080,8443
repository.port-paths.myapp=8080:/app,8443:/admin
```

When a container maps port 8080 to host port 10000, the UI renders a clickable link to `http://<host>:10000/app`.

---

## Container Expiration

| Property | Description | Default |
|----------|-------------|---------|
| `expiration.storage.file` | JSON file for expiration persistence | `data/expirations.json` |

---

## Database Features

### General

| Property | Description | Default |
|----------|-------------|---------|
| `database.listing.enabled` | Show database dropdown in container creation | false |
| `database.deletion-on-expiration.enabled` | Allow auto-delete database on container expiration | false |

### PostgreSQL Connection (per-repository)

| Property | Description |
|----------|-------------|
| `repository.pg-host.<repo>` | PostgreSQL host |
| `repository.pg-port.<repo>` | PostgreSQL port |
| `repository.pg-user.<repo>` | PostgreSQL user |
| `repository.pg-password.<repo>` | PostgreSQL password |
| `repository.pg-db-env-var.<repo>` | Env var name passed to container with the database name |
| `repository.pg-image.<repo>` | Postgres Docker image for restore/snapshot operations (use `none` for local tooling) |

### Example

```properties
database.listing.enabled=true
database.deletion-on-expiration.enabled=true

repository.pg-host.myapp=192.168.1.100
repository.pg-port.myapp=5432
repository.pg-user.myapp=postgres
repository.pg-password.myapp=secret
repository.pg-db-env-var.myapp=DB_NAME
repository.pg-image.myapp=postgres:16
```

---

## Database Dumps

| Property | Description | Default |
|----------|-------------|---------|
| `database.dump.enabled` | Enable dump feature | false |
| `database.dump.upload-password` | Password for uploading dumps | — |
| `database.dump.operations-password` | Password for restore/delete/migration operations | — |
| `database.dump.storage.dir` | Storage directory for dump files | `data/dumps/` |
| `database.dump.max-size-mb` | Maximum dump file size (MB) | 1500 |
| `database.dump.metadata.file` | JSON file for dump metadata | `data/dumps-metadata.json` |

---

## Database Snapshots

| Property | Description | Default |
|----------|-------------|---------|
| `database.snapshot.storage.dir` | Storage directory for snapshots | `data/snapshots/` |
| `database.snapshot.metadata.file` | JSON file for snapshot metadata | `data/snapshots-metadata.json` |
| `database.snapshot.max-size-mb` | Maximum snapshot size (MB) | 1500 |

---

## Post-Restore Scripts

| Property | Description | Default |
|----------|-------------|---------|
| `post-restore-scripts.enabled` | Enable post-restore SQL scripts | false |
| `post-restore-scripts.on-failure` | `stop` (abort) or `continue` (log and proceed) | `stop` |
| `repository.post-restore-mandatory-dir.<repo>` | Directory for mandatory scripts | — |
| `repository.post-restore-optional-dir.<repo>` | Directory for optional scripts | — |

---

## Database Migrations

| Property | Description | Default |
|----------|-------------|---------|
| `database.migration.enabled` | Enable migration feature | false |
| `database.migration.api-url` | Global API URL template (`{sourceVersion}`, `{targetVersion}` placeholders) | — |
| `repository.migration-api-url.<repo>` | Per-repository API URL override | — |

---

## Container Terminal

| Property | Description | Default |
|----------|-------------|---------|
| `container.terminal.enabled` | Enable the interactive terminal feature | `false` |
| `container.terminal.password` | Password for terminal access | — |
| `container.terminal.password.required` | Whether password is mandatory | `false` |
| `container.terminal.default-shell` | Preferred shell (falls back to `/bin/sh`) | `/bin/bash` |
| `container.terminal.max-sessions` | Maximum concurrent terminal sessions | `5` |
| `container.terminal.idle-timeout-minutes` | Auto-close idle sessions after this period | `30` |
| `container.terminal.upload.enabled` | Enable file upload to container from terminal | `false` |
| `container.terminal.upload.max-size-mb` | Maximum upload file size (MB) | `100` |
| `container.terminal.upload.default-path` | Default destination path inside the container | `/tmp` |

See [Container Terminal](container-terminal.md) for full documentation.

---

## Passwords

The application uses a multi-tier password system:

| Password | Used For | Property |
|----------|----------|----------|
| **Upload password** | Uploading new dumps | `database.dump.upload-password` |
| **Operations password** | Restoring dumps, deleting dumps/snapshots, running migrations, pruning images | `database.dump.operations-password` |
| **Scheduling password** | Creating, deleting, toggling, and executing schedules | `container.scheduling.password` |
| **Terminal password** | Terminal access and file upload to containers | `container.terminal.password` |

---

## Log Analyzer

| Property | Description | Default |
|----------|-------------|---------|
| `log.analyzer.enabled` | Enable the Log Analyzer feature | `false` |
| `log.analyzer.max-file-size-mb` | Maximum upload size per file (MB) | `500` |
| `log.analyzer.max-files` | Maximum analyses kept in memory | `5` |
| `log.analyzer.file-ttl-minutes` | Auto-eviction time (minutes) | `120` |
| `log.analyzer.slow-threshold-ms` | API call slow threshold (ms) | `1000` |
| `log.analyzer.container-tail` | Lines fetched for container analysis | `10000` |
| `log.analyzer.default-preset` | Default parsing preset | `WILDFLY` |

### Memory Limits

| Property | Description | Default |
|----------|-------------|---------|
| `log.analyzer.critical-issues.max-matches` | Total critical issue matches across all categories | `10000` |
| `log.analyzer.custom-fields.max-matches` | Stored matches per custom field | `10000` |
| `log.analyzer.npe-analysis.max-occurrences` | NPE occurrences with stack traces | `5000` |
| `log.analyzer.exception-analysis.max-occurrences` | Exception occurrences with stack traces | `5000` |

See [Log Analyzer](log-analyzer.md) for full feature documentation.

---

## UI / Server

| Property | Description | Default |
|----------|-------------|---------|
| `ui.locale` | Locale for date/time pickers (e.g., `pt-br`, `en`, `es`) | — |
| `quarkus.http.limits.max-body-size` | Maximum HTTP request body size | 1.5G |
| `quarkus.resteasy.path` | REST API base path | `/api` |
