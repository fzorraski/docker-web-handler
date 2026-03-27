# Container Terminal

## Overview

Docker Web Handler provides an interactive terminal directly in the browser, allowing you to run commands inside a running container without needing SSH or local Docker CLI access. The terminal is powered by xterm.js on the frontend and Docker's exec API on the backend, connected via WebSocket for real-time bidirectional communication.

---

## Features

- **Interactive shell:** Full TTY-attached shell session (defaults to `/bin/bash`, falls back to `/bin/sh`)
- **Real-time I/O:** Bidirectional WebSocket connection for low-latency input/output
- **Terminal resizing:** Automatically adapts to dialog and window size changes
- **Dark/light theme:** Terminal colors match the application's current theme
- **Full-screen mode:** Expand the terminal dialog to fill the entire screen
- **Clickable links:** URLs in terminal output are rendered as clickable links
- **Session management:** Configurable max concurrent sessions and idle timeout
- **Password protection:** Optional password requirement for terminal access
- **File upload:** Send files from your machine into the container (see [File Upload](#file-upload) below)

---

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `container.terminal.enabled` | Enable the terminal feature | `false` |
| `container.terminal.password` | Password for terminal access | — |
| `container.terminal.password.required` | Whether password is mandatory | `false` |
| `container.terminal.default-shell` | Preferred shell (falls back to `/bin/sh`) | `/bin/bash` |
| `container.terminal.max-sessions` | Maximum concurrent terminal sessions | `5` |
| `container.terminal.idle-timeout-minutes` | Auto-close idle sessions after this period | `30` |
| `container.terminal.upload.enabled` | Enable file upload to container | `false` |
| `container.terminal.upload.max-size-mb` | Maximum upload file size (MB) | `100` |
| `container.terminal.upload.default-path` | Default destination path inside the container | `/tmp` |

All properties can be overridden via environment variables (e.g., `CONTAINER_TERMINAL_ENABLED=true`).

### Example

```properties
container.terminal.enabled=true
container.terminal.password=mysecret
container.terminal.password.required=true
container.terminal.default-shell=/bin/bash
container.terminal.max-sessions=10
container.terminal.idle-timeout-minutes=60
container.terminal.upload.enabled=true
container.terminal.upload.max-size-mb=200
container.terminal.upload.default-path=/tmp
```

---

## API

### Authorize Terminal Session

```
POST /api/containers/terminal/authorize
Content-Type: application/json

{
  "containerId": "abcdef1234...",
  "password": "mysecret"
}
```

**Response:**

```json
{ "ticket": "550e8400-e29b-41d4-a716-446655440000" }
```

Returns a one-time ticket (UUID) with a 5-minute TTL. The ticket is consumed when the WebSocket connection is established.

### WebSocket Connection

```
ws://<host>/api/containers/terminal/{ticket}
```

**Server messages (JSON):**

| Type | Description |
|------|-------------|
| `connected` | Session established successfully |
| `output` | Terminal output (base64-encoded bytes in `data` field) |
| `exit` | Shell process exited (`code`, `message` fields) |
| `error` | Error occurred (`message` field) |
| `pong` | Response to client ping |

**Client messages (JSON):**

| Type | Description |
|------|-------------|
| `input` | Keyboard input (`data` field, base64-encoded) |
| `resize` | Terminal resize (`cols`, `rows` fields) |
| `ping` | Keep-alive ping |

### Upload File to Container

```
POST /api/containers/{containerId}/upload
Content-Type: multipart/form-data

Form fields:
  - file: (binary) the file to upload
  - password: terminal password
  - remotePath: destination directory inside the container (e.g., /tmp)
```

**Response (success):**

```json
{ "filename": "script.sh", "remotePath": "/tmp" }
```

**Response (error):**

```json
{ "error": "File exceeds the maximum size of 100 MB." }
```

The file is streamed to a temporary file on the host with bounded size enforcement (aborts mid-transfer if the limit is exceeded), then copied into the container using Docker's archive copy API. The temporary file is always deleted after the operation.

---

## UI

### Opening a Terminal

1. Right-click a running container in the containers table (or use the action menu)
2. Click **Open Terminal**
3. If password is required, enter the terminal password in the dialog
4. The terminal dialog opens and connects automatically

### Terminal Dialog

The dialog includes:

- **Title bar:** Container name, connection status indicator (pulsing green dot when connected), full-screen toggle
- **Terminal viewport:** Interactive shell with scrollback (5000 lines)
- **Action bar:** Upload button (when enabled), Close button

The terminal automatically reconnects to the shell's dimensions when the dialog is resized or toggled to full-screen.

### File Upload

When `container.terminal.upload.enabled=true`, an upload icon button appears in the action bar while connected.

1. Click the upload icon to expand the upload panel
2. Click **Select file** to choose a file from your machine
3. Edit the **Destination path** if needed (defaults to the configured `container.terminal.upload.default-path`)
4. Click the **Send** button to start the upload
5. A progress bar shows upload progress in real-time
6. A success or error message appears when the operation completes
7. Click the **X** button to close the upload panel

**Validations:**

- File size is checked client-side before upload and server-side during streaming
- Destination path must be absolute (start with `/`), no path traversal (`..`), and only contain safe characters
- Filename must not contain path separators

---

## Architecture

### Backend

- **`ContainerTerminalEndpoint`** (`@ServerEndpoint`) — WebSocket endpoint that creates a Docker exec session, streams I/O, and manages the session lifecycle
- **`DockerTerminalAdapter`** — Implements `DockerTerminalPort`; creates exec sessions via docker-java, manages piped I/O streams with virtual threads, handles shell fallback
- **`DockerTerminalPort`** — Port interface defining `createExecSession`, `startExecSession`, `resizeExec`, `isContainerRunning`, and `copyFileToContainer`
- **`TerminalSessionManager`** — Tracks active WebSocket sessions, enforces max-sessions limit, schedules hourly idle-timeout cleanup
- **`ContainerFileUploadController`** — REST endpoint for multipart file upload; validates inputs, streams to temp file, copies to container via `copyArchiveToContainerCmd`
- **`RequestStash`** — Stores terminal tickets with 5-minute TTL and automatic eviction

### Frontend

- **`ContainerTerminalDialog`** — xterm.js-based terminal with WebSocket connection, theme support, resize observer, and collapsible file upload panel
- **`useTerminalAuth`** — Hook managing the password dialog flow, ticket acquisition, and retaining `containerId`/`password` for file upload auth
- **`terminalService.ts`** — `authorizeTerminal()` for ticket acquisition, `connectTerminal()` for WebSocket connection, `uploadFileToContainer()` for XHR-based file upload with progress
