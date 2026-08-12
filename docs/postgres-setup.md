# PostgreSQL Persistence Setup

Since the persistence migration, the application stores its own state (users,
tenants, roles, schedules, dump/snapshot metadata, managed-database metadata,
expirations, runtime settings, audit trail, login sessions, counters) in a
dedicated PostgreSQL database instead of JSON files under `data/`.

This database is **for the app itself** — it is unrelated to the managed
PostgreSQL servers configured per repository (`REPOSITORY_PG_HOST_*`).

Developer-facing documentation of the persistence layer (schema, backends,
concurrency guarantees, importer internals) is in [persistence.md](persistence.md).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PERSISTENCE_BACKEND` | `postgres` | `postgres` or `file` (legacy JSON fallback, removed in a future release) |
| `APP_DB_HOST` | `localhost` | App database host |
| `APP_DB_PORT` | `5432` | App database port |
| `APP_DB_NAME` | `dockerwebhandler` | Database name |
| `APP_DB_USER` | `dockerwebhandler` | Database user |
| `APP_DB_PASSWORD` | `dockerwebhandler` | Database password |
| `JSON_IMPORT_ENABLED` | `true` | One-time import of legacy `data/*.json` files on first boot |

The schema is created and evolved automatically by Flyway on startup.

### Hiding the database container

The app database runs as a container on the same Docker daemon this tool
manages, so it would otherwise show up in the container list. Add its image to
`hidden.images` (`HIDDEN_IMAGES`) to filter it out of the container listing,
the image listing and prune candidates:

```yaml
HIDDEN_IMAGES: "postgres:17-alpine"
```

Hidden images are implicitly **protected** as well, so the container cannot be
stopped or removed by anyone who knows its id. Use the tagged form: a bare
`postgres` would also hide managed per-repository PG containers
(`REPOSITORY_PG_IMAGE_*`).

## Migrating an existing installation

On the first boot with the postgres backend, the app automatically imports the
legacy JSON files (`users.json`, `tenants.json`, `roles.json`,
`schedules.json`, metadata files, counters, …) into the database and renames
each imported file to `*.imported`. The import is idempotent — a
`json_import_history` marker table prevents double imports — and a failed
store aborts startup after rolling itself back, so a crash can never leave a
half-imported auth store.

Notes:
- Dump and snapshot **binaries** stay on disk (`data/dumps/`, `data/snapshots/`);
  only their metadata moves to the database. Keep the data volume mounted.
- The old `data/audit.log` is not imported; new audit entries go to the
  `audit_log` table. The old file remains readable on disk.
- Login sessions are now durable: restarting the app no longer logs everyone out.
- Backups: `pg_dump` of the app database replaces copying `data/*.json`
  (still copy `data/dumps` + `data/snapshots` for the binaries).

## docker-compose example

See `docker-compose.example.yml`. Minimal sidecar:

```yaml
services:
  docker-web-handler:
    # ... existing config ...
    environment:
      APP_DB_HOST: app-db
      APP_DB_PORT: "5432"
      APP_DB_NAME: dockerwebhandler
      APP_DB_USER: dockerwebhandler
      APP_DB_PASSWORD: change-me
    depends_on:
      app-db:
        condition: service_healthy

  app-db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: dockerwebhandler
      POSTGRES_USER: dockerwebhandler
      POSTGRES_PASSWORD: change-me
    volumes:
      - dwh-appdb:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dockerwebhandler -d dockerwebhandler"]
      interval: 5s
      timeout: 3s
      retries: 12

volumes:
  dwh-appdb:
```

## Development

In dev (`./mvnw compile quarkus:dev`) and tests, no JDBC URL is configured, so
Quarkus **Dev Services** starts a disposable PostgreSQL container
automatically (requires Docker, which the app needs anyway). The JSON import
is disabled in dev/test so your local `data/` files are never consumed by a
throwaway database. To run dev mode against the legacy files instead:
`PERSISTENCE_BACKEND=file ./mvnw compile quarkus:dev`.

## Fallback

If the postgres backend misbehaves in the field, fall back to the JSON files:

```bash
PERSISTENCE_BACKEND=file
APP_DB_ACTIVE=false   # deactivates the datasource + Flyway: boots with no PostgreSQL at all
```

Rename any `*.imported` files under `data/` back to their original names
first — they contain the pre-migration state. Note that changes made while on
the postgres backend are **not** written back to the files, and file-mode
sessions are in-memory again (restart logs everyone out). The file backend is
scheduled for removal one release after the migration ships.
