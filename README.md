# Docker Web Handler

A web application for managing Docker containers and images from the browser. Built with **Quarkus** (Java) and **React** (TypeScript), it communicates with the Docker daemon via [docker-java](https://github.com/docker-java/docker-java) over the local socket.

## Features

- **Container Management** -- Create, start, stop, remove containers from a whitelist of allowed repositories
- **Container Upgrade** -- Change a container's image tag while preserving configuration, ports, expiration, and schedules
- **Image Management** -- List, pull, and remove Docker images with multi-registry tag browsing (Docker Hub, GitLab, GHCR, and any OCI registry)
- **Interactive Terminal** -- Browser-based shell sessions into running containers via WebSocket + xterm.js, with file upload support
- **Database Operations** -- List PostgreSQL databases, upload/restore dumps, create snapshots, run post-restore scripts
- **Database Insights** -- Health metrics, activity monitoring, table/index analysis, top queries, temp file tracking, SQL query runner, EXPLAIN, and downloadable HTML reports
- **Database Migration** -- Manual or API-driven SQL migrations between versions
- **Container Scheduling** -- Schedule start/stop/create actions with cron expressions and conflict detection
- **Container Expiration** -- Auto-remove containers (and optionally drop databases) after a configurable TTL
- **Resource Monitoring** -- Real-time CPU, memory, network, and disk stats per container; server-wide memory and disk monitoring
- **Memory Guard** -- Prevents container creation when host memory is below a configurable threshold
- **Log Analyzer** -- Upload or snapshot container logs for API call pairing, response time stats, job tracking, anomaly detection, exception grouping, duplicate request detection, and custom field extraction. Presets for WildFly, Quarkus, Spring Boot, and Nginx.
- **Webhook Notifications** -- POST notifications to Slack or any endpoint on container/restore events with HMAC-SHA256 signing
- **CI/CD API** -- Programmatic environment creation for pipelines with API key authentication
- **Authentication & RBAC** -- Optional login authentication: a single shared password, or full per-user accounts with roles, permissions, and tenants (team scopes with per-tenant repository/database entitlements) managed in the admin UI
- **PostgreSQL Persistence** -- All application state (users, schedules, metadata, audit trail, sessions) lives in a dedicated PostgreSQL database with automatic schema management and a one-time import of legacy JSON files; login sessions survive restarts
- **i18n** -- English, Portuguese (BR), and Spanish

## Quick Start

The fastest way to a running instance is Docker Compose: the app plus its own
PostgreSQL database (used for application state — unrelated to any databases
you manage *with* the app).

**1.** Save this as `docker-compose.yml` (or start from
[`docker-compose.example.yml`](docker-compose.example.yml)):

```yaml
services:
  docker-web-handler:
    image: fabriciozrk/docker-web-handler:latest
    restart: unless-stopped
    ports:
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - dwh-data:/deployments/data
    environment:
      # which image repositories users may run containers from
      ALLOWED_RUN_REPOSITORIES: nginx,myorg/myapp

      # application database
      APP_DB_HOST: app-db
      APP_DB_PASSWORD: change-me

      # login with per-user accounts (see Security below)
      APP_AUTH_ENABLED: "true"
      APP_AUTH_MODE: rbac
      RBAC_ADMIN_PASSWORD: change-me-too
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
  dwh-data:
  dwh-appdb:
```

**2.** Start it and log in:

```bash
docker compose up -d
```

Open http://localhost:8080 and sign in as `admin` / the `RBAC_ADMIN_PASSWORD`
you set (the super admin is created automatically on first boot). From the
admin area you can create users, roles, and tenants. The database schema is
created automatically; nothing to provision beyond the compose file.

**3.** (Optional) For the full configuration surface, copy
[`.env.example`](.env.example) to `.env` and reference it from the compose
file — every feature flag is documented there.

> **Requires Docker socket access.** The container needs `/var/run/docker.sock`
> mounted to communicate with the Docker daemon.

**Upgrading from a JSON-file installation?** Keep the `dwh-data` volume: on
first boot the app imports the legacy `data/*.json` files into PostgreSQL
automatically (they are renamed to `*.imported`). Details in
[docs/postgres-setup.md](docs/postgres-setup.md).

## Development Setup

**Prerequisites:** Java 25+, Maven, Node.js 20+, Docker

```bash
# Clone and run in dev mode (live reload for both backend and frontend)
git clone https://github.com/fabriciozrk/docker-web-handler.git
cd docker-web-handler
./mvnw compile quarkus:dev
```

- Backend: http://localhost:8080
- Frontend dev server: http://localhost:5173 (proxied to backend)

In dev mode and in tests, a disposable PostgreSQL container for the app's own
store is started automatically by Quarkus Dev Services — no manual database
setup needed (it requires Docker, which the app needs anyway).

### Other Commands

| Command | Description |
|---------|-------------|
| `./mvnw test` | Run tests |
| `./mvnw package` | Build production jar |
| `java -jar target/docker-web-handler-1.0.0-SNAPSHOT-runner.jar` | Run packaged jar |
| `./mvnw package -Pnative` | Build native executable (requires GraalVM) |
| `cd src/main/webui && npm run dev` | Frontend only |
| `cd src/main/webui && npm run build` | Frontend production build |

## Configuration

All configuration is done via environment variables. See [`.env.example`](.env.example) for the full list with descriptions.

Every property in [`application.properties`](src/main/resources/application.properties) can be overridden by an environment variable using this naming convention: dots and hyphens become underscores, all uppercase.

```
docker.connect-timeout  ->  DOCKER_CONNECT_TIMEOUT
```

Per-repository properties use the pattern:

```
REPOSITORY_<SETTING>_<REPO_NAME>
```

### Key Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket path |
| `ALLOWED_RUN_REPOSITORIES` | *(empty)* | Comma-separated image repos users can run |
| `APP_DB_HOST` / `APP_DB_PORT` | `localhost` / `5432` | Application database (the app's own PostgreSQL store) |
| `APP_DB_NAME` / `APP_DB_USER` / `APP_DB_PASSWORD` | `dockerwebhandler` | Application database name and credentials |
| `PERSISTENCE_BACKEND` | `postgres` | `postgres` or `file` (legacy JSON fallback) |
| `APP_AUTH_ENABLED` | `false` | Enable login-based authentication |
| `APP_AUTH_MODE` | `password` | `password` (shared) or `rbac` (per-user accounts) |
| `APP_AUTH_PASSWORD` | *(empty)* | Login password (`password` mode) |
| `RBAC_ADMIN_USERNAME` / `RBAC_ADMIN_PASSWORD` | `admin` / *(empty)* | Seed super admin for `rbac` mode (first boot only) |
| `APP_AUTH_SESSION_TIMEOUT_MINUTES` | `480` | Session TTL |
| `DATABASE_LISTING_ENABLED` | `false` | Show PostgreSQL databases in the UI |
| `DATABASE_DUMP_ENABLED` | `false` | Enable dump upload/restore |
| `DATABASE_MANAGED_ENABLED` | `false` | Enable managed databases tab with live metrics |
| `DATABASE_QUERY_ENABLED` | `false` | Enable SQL query runner in database insights |
| `DATABASE_QUERY_STATS_RESET_ENABLED` | `false` | Enable statistics reset buttons |
| `CONTAINER_SCHEDULING_ENABLED` | `true` | Enable container scheduling |
| `CONTAINER_TERMINAL_ENABLED` | `true` | Enable browser terminal |
| `CONTAINER_MEMORY_GUARD_ENABLED` | `true` | Block creation when memory is low |
| `WEBHOOK_ENABLED` | `false` | Enable webhook notifications |
| `WEBHOOK_URL` | *(empty)* | Webhook endpoint URL |
| `CI_API_ENABLED` | `false` | Enable CI/CD API |
| `CI_API_KEY` | *(empty)* | API key for CI authentication |
| `LOG_ANALYZER_ENABLED` | `false` | Enable log analyzer feature |

## Security

### Authentication

Authentication is **disabled by default** for backward compatibility. Two modes are available:

**RBAC mode (recommended)** — per-user accounts with roles and permissions,
managed in the admin UI:

```bash
APP_AUTH_ENABLED=true
APP_AUTH_MODE=rbac
RBAC_ADMIN_PASSWORD=<strong-password>   # seeds the super admin on first boot
```

Sign in as `admin`, then create users and assign roles (built-in:
`SUPER_ADMIN`, `ADMIN`, `OPERATOR`, `VIEWER`, plus custom roles with any
permission combination). **Tenants** scope teams to their own containers,
dumps, snapshots, schedules, and databases — and can be restricted to a subset
of the configured repositories and database connections. Sessions are stored
in the application database and survive restarts.

**Password mode (legacy)** — one shared password for everyone:

```bash
APP_AUTH_ENABLED=true
APP_AUTH_MODE=password
APP_AUTH_PASSWORD=your-strong-password-here
```

When enabled, all API endpoints require a valid session cookie obtained via `/api/auth/login`.

### Feature Passwords

> In RBAC mode these are ignored — role permissions replace them.

Several features have independent password protection (password mode only):

| Feature | Password Variable | Required Variable |
|---------|-------------------|-------------------|
| Dump upload | `DATABASE_DUMP_UPLOAD_PASSWORD` | `DATABASE_DUMP_UPLOAD_PASSWORD_REQUIRED` |
| Dump/snapshot operations | `DATABASE_DUMP_OPERATIONS_PASSWORD` | `DATABASE_DUMP_OPERATIONS_PASSWORD_REQUIRED` |
| Scheduling | `CONTAINER_SCHEDULING_PASSWORD` | `CONTAINER_SCHEDULING_PASSWORD_REQUIRED` |
| Terminal | `CONTAINER_TERMINAL_PASSWORD` | `CONTAINER_TERMINAL_PASSWORD_REQUIRED` |

### Recommended Production Settings

```bash
# per-user authentication with roles and tenants
APP_AUTH_ENABLED=true
APP_AUTH_MODE=rbac
RBAC_ADMIN_PASSWORD=<strong-password>

# application database
APP_DB_PASSWORD=<strong-password>

CONTAINER_MEMORY_GUARD_ENABLED=true
WEBHOOK_ALLOW_HTTP=false
```

With legacy password mode instead, also set the feature passwords
(`DATABASE_DUMP_OPERATIONS_PASSWORD`, `CONTAINER_SCHEDULING_PASSWORD`,
`CONTAINER_TERMINAL_PASSWORD` and their `*_REQUIRED` flags).

### Docker Socket Access

This application requires access to the Docker daemon socket. This grants significant privileges -- the application can create, start, stop, and remove containers on the host. Deploy it in a trusted environment and restrict network access appropriately.

### Registry Credentials

Registry credentials are stored in environment variables. Use Docker secrets or a secrets manager in production. Never commit credentials to version control.

## Data & Persistence

Application state lives in a dedicated **PostgreSQL** database (users, tenants,
roles, schedules, dump/snapshot metadata, expirations, runtime settings, audit
trail, login sessions). The schema is created and evolved automatically by
Flyway — nothing to provision beyond the database itself.

- **Upgrading from an older (JSON-file) version:** legacy `data/*.json` files
  are imported automatically on the first boot and renamed to `*.imported`.
- **Dump/snapshot binaries stay on disk** under `data/dumps/` and
  `data/snapshots/` — keep the data volume mounted.
- **Backups:** `pg_dump` of the app database + a copy of `data/dumps` and
  `data/snapshots`.
- This database is unrelated to the PostgreSQL servers the app *manages*
  (`REPOSITORY_PG_HOST_*`).

Operator guide: [docs/postgres-setup.md](docs/postgres-setup.md) · Developer
reference: [docs/persistence.md](docs/persistence.md)

## Architecture

```
Backend (Quarkus / JAX-RS)          Frontend (React / MUI)
br.com.fzdevx/                      src/main/webui/src/
├── domain/                         ├── pages/
│   ├── model/                      ├── components/
│   ├── exception/                  ├── services/
│   └── shared/                     ├── hooks/
├── application/                    ├── i18n/
│   ├── usecase/                    └── theme/
│   ├── port/
│   └── dto/
├── infrastructure/
│   ├── docker/
│   ├── persistence/
│   ├── registry/
│   ├── config/
│   └── util/
└── interfaces/
    └── rest/
```

The backend follows **Clean Architecture** with the dependency rule: `domain` <- `application` <- `infrastructure` / `interfaces`. The frontend is a React SPA served by Quarkus Quinoa with React Router for client-side routing.
