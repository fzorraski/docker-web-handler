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
- **Authentication** -- Optional login-based session authentication for all API access
- **i18n** -- English, Portuguese (BR), and Spanish

## Quick Start with Docker

```bash
docker run -d \
  -p 8080:8080 \
  --restart=always \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v dwh-data:/deployments/data \
  -e ALLOWED_RUN_REPOSITORIES=nginx,myorg/myapp \
  fabriciozrk/docker-web-handler:latest
```

Then open http://localhost:8080.

> **Requires Docker socket access.** The container needs `/var/run/docker.sock` mounted to communicate with the Docker daemon.

### Using Docker Compose

1. Copy `.env.example` to `.env` and fill in your values
2. Create a `docker-compose.yml` referencing your `.env` (see [Configuration](#configuration) below)
3. Run `docker compose up -d`

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
| `APP_AUTH_ENABLED` | `false` | Enable login-based authentication |
| `APP_AUTH_PASSWORD` | *(empty)* | Password for login (required when auth enabled) |
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

Authentication is **disabled by default** for backward compatibility. To enable:

```bash
APP_AUTH_ENABLED=true
APP_AUTH_PASSWORD=your-strong-password-here
```

When enabled, all API endpoints require a valid session cookie obtained via `/api/auth/login`.

### Feature Passwords

Several features have independent password protection:

| Feature | Password Variable | Required Variable |
|---------|-------------------|-------------------|
| Dump upload | `DATABASE_DUMP_UPLOAD_PASSWORD` | `DATABASE_DUMP_UPLOAD_PASSWORD_REQUIRED` |
| Dump/snapshot operations | `DATABASE_DUMP_OPERATIONS_PASSWORD` | `DATABASE_DUMP_OPERATIONS_PASSWORD_REQUIRED` |
| Scheduling | `CONTAINER_SCHEDULING_PASSWORD` | `CONTAINER_SCHEDULING_PASSWORD_REQUIRED` |
| Terminal | `CONTAINER_TERMINAL_PASSWORD` | `CONTAINER_TERMINAL_PASSWORD_REQUIRED` |

### Recommended Production Settings

```bash
APP_AUTH_ENABLED=true
APP_AUTH_PASSWORD=<strong-password>
DATABASE_DUMP_OPERATIONS_PASSWORD_REQUIRED=true
DATABASE_DUMP_OPERATIONS_PASSWORD=<strong-password>
CONTAINER_SCHEDULING_PASSWORD_REQUIRED=true
CONTAINER_SCHEDULING_PASSWORD=<strong-password>
CONTAINER_TERMINAL_PASSWORD_REQUIRED=true
CONTAINER_TERMINAL_PASSWORD=<strong-password>
CONTAINER_MEMORY_GUARD_ENABLED=true
WEBHOOK_ALLOW_HTTP=false
```

### Docker Socket Access

This application requires access to the Docker daemon socket. This grants significant privileges -- the application can create, start, stop, and remove containers on the host. Deploy it in a trusted environment and restrict network access appropriately.

### Registry Credentials

Registry credentials are stored in environment variables. Use Docker secrets or a secrets manager in production. Never commit credentials to version control.

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
