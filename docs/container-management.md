# Container Management

## Overview

Docker Web Handler provides full lifecycle management for Docker containers through a web interface. You can list, start, stop, and remove containers — all from the browser without needing terminal access.

The application automatically filters itself out of container listings using the `Constants.DOCKER_WEB_HANDLER_IMAGE` identifier (`fabriciozrk/docker-web-handler`).

---

## Features

### List Containers

Displays all containers (running and stopped) in a searchable, sortable table with the following columns:

| Column | Description |
|--------|-------------|
| Name | Container name |
| Image | Repository name |
| Tag | Image tag |
| Status | Running / Stopped / Exited (color-coded chip) |
| Ports | Mapped host:container ports (clickable hyperlinks when `portPaths` is configured) |
| Created | Container creation timestamp |
| Database | Associated PostgreSQL database name (if any) |
| Expiration | Scheduled expiration time (if set) |
| Actions | Start, Stop, Remove, Logs, Snapshot, Migration |

The table supports:
- **Search** by container name, image, or status
- **Sorting** by any column
- **Column visibility** toggle via a menu

### Start Container

Starts a stopped container by its ID. Only available when the container is in a stopped/exited state.

### Stop Container

Stops a running container by its ID. Only available when the container is currently running.

### Remove Container

Removes a container with the following behavior:
1. Cancels any scheduled expiration
2. Stops the container if it is currently running
3. Removes the container

Removal is streamed via SSE so the UI shows real-time progress.

---

## API Endpoints

### List Containers

```
GET /api/containers/list
```

**Response:** Array of `DockerContainer` objects.

```json
[
  {
    "containerId": "abc123...",
    "image": "myapp",
    "tag": "1.0.0",
    "command": "/entrypoint.sh",
    "created": "2026-03-19T10:00:00",
    "status": "running",
    "ports": "0.0.0.0:10000->8080/tcp",
    "name": "myapp-dev",
    "databaseName": "myapp_db",
    "expiresAt": "2026-03-19T18:00:00",
    "deleteDatabaseOnExpiration": false
  }
]
```

### Start Container

```
POST /api/containers/start
Content-Type: application/json

{ "containerId": "abc123..." }
```

**Response:** `true` on success.

### Stop Container

```
POST /api/containers/stop
Content-Type: application/json

{ "containerId": "abc123..." }
```

**Response:** `true` on success.

### Remove Container (SSE)

```
GET /api/containers/sse/remove/{containerId}
Accept: text/event-stream
```

**Events:**
- `INFO` — Progress messages (cancelling expiration, stopping, removing)
- `PROGRESS` — Step completion updates
- `SUCCESS` — Container removed successfully
- `ERROR` — Failure details

---

## UI

The container list is the default landing page at `/`. The table auto-refreshes and provides inline action buttons for each container row.

Port numbers are rendered as clickable links. When `repository.port-paths.<repo>` is configured (e.g., `8080:/app`), the link opens `http://<host>:<mappedPort>/app`.
