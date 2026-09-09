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
- **Image attachments:** Paste or drag-and-drop an image into the terminal; it is uploaded into the container and its path is typed into the prompt (see [Image Attachments](#image-attachments) below)

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
| `container.terminal.upload.image.enabled` | Enable pasting/dropping images into the terminal (independent of file upload) | `false` |
| `container.terminal.upload.image.path` | Directory inside the container for pasted/dropped images (runtime-editable) | `/tmp` |
| `container.terminal.upload.image.max-size-mb` | Maximum size (MB) for pasted/dropped images | `10` |

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
container.terminal.upload.image.enabled=true
container.terminal.upload.image.path=/tmp/attachments
container.terminal.upload.image.max-size-mb=10
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

**Missing destination directories** (both endpoints): the copy is attempted first; when the Engine reports the directory does not exist, it is created with `mkdir -p` as root (the same privilege the copy itself runs with) and the copy is retried once. A directory that cannot be created yields a 500 whose message names the directory. The common case therefore costs a single Docker API call.

**Rate limiting:** repeated wrong passwords trigger the terminal password rate limiter, which surfaces as `429 Too Many Requests` with a retry hint, not as a generic failure.

### Upload Image Attachment

```
POST /api/containers/{containerId}/upload/image
Content-Type: multipart/form-data

Form fields:
  - file: (binary) the image (PNG, JPEG, GIF, or WebP)
  - password: terminal password
```

**Response (success):**

```json
{ "filename": "clip-1725800000000-3fa9c1b2.png", "remotePath": "/tmp", "path": "/tmp/clip-1725800000000-3fa9c1b2.png" }
```

**Response (error):**

```json
{ "error": "Unsupported image format. Use PNG, JPEG, GIF, or WebP." }
```

The server ignores the client-provided filename and MIME type. It detects the format from the file's leading bytes before writing anything to disk, generates a collision-safe name (`clip-<epoch-ms>-<random>.<ext>`), and copies the image into the image upload directory (`container.terminal.upload.image.path`, default `/tmp`, editable at runtime in the admin Settings tab). The size limit is `container.terminal.upload.image.max-size-mb`, independent of the general upload limit. Requires `container.terminal.upload.image.enabled=true`; the generic `container.terminal.upload.enabled` flag is not needed. Both flags can also be toggled at runtime in the admin Settings tab.

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

### Image Attachments

A terminal is a text stream, so images cannot be sent "through" it. Instead, when `container.terminal.upload.image.enabled=true`, the terminal turns a pasted or dropped image into a file inside the container and types its path into the prompt. This lets a REPL running in the terminal (for example an LLM assistant) read the image from disk.

1. Focus the terminal and paste an image from the clipboard (a screenshot, for example) with **Ctrl+V** on Linux/Windows or **⌘V** on macOS, or drag an image file onto the terminal viewport
2. A progress strip appears below the terminal while the image uploads
3. On success, the full path (e.g. `/tmp/clip-1725800000000-3fa9c1b2.png`) followed by a space is typed into the current input line, and the terminal regains focus
4. Keep typing your question and press Enter as usual

Several images pasted in a row are uploaded one at a time, in order, so their paths appear in the prompt in the same order.

Plain-text pastes are not intercepted and behave as before. When the clipboard carries both text and a rendered preview image (typical for spreadsheet or browser copies), the text wins and is pasted into the shell.

**Keyboard shortcuts:** xterm.js normally turns Ctrl+V into the `^V` control character on Linux and Windows, so no browser paste happens. While image attachments are enabled, the dialog hands the platform's native paste chord (Ctrl+V or Ctrl+Shift+V on Linux/Windows, ⌘V on macOS) back to the browser, so both images and text paste with Ctrl+V; the `^V` keystroke (readline quoted-insert, vim visual block) is then not delivered to the shell on Linux/Windows. With the feature disabled the terminal keeps stock xterm behavior. Held-down chords are not auto-repeated into multiple pastes. Shift+Insert and middle-click keep their browser-default behavior.

**Dropping other files:** dropping a non-image file on the terminal is swallowed with an "unsupported format" notice; it never navigates the tab away. An upload that is still running when the dialog is closed is discarded, so its path can never be typed into a different container's prompt, and a result that arrives after the terminal disconnected is still shown so the file can be reused after reconnecting. Unsupported formats and oversized images show an error strip instead of uploading. Attachments are regular files in the container and are not removed automatically; the default directory is `/tmp`, so they are discarded with the container. Admins can point the directory elsewhere (for example `/workspace/attachments`) in the Settings tab; the value must be an absolute path without `..`.

**Integrating with a REPL:** the tool running in the terminal receives only the path as text. It is responsible for detecting image paths in the user's message, reading the files, and passing them to the model as image content.

---

## Architecture

### Backend

- **`ContainerTerminalEndpoint`** (`@ServerEndpoint`) — WebSocket endpoint that creates a Docker exec session, streams I/O, and manages the session lifecycle
- **`DockerTerminalAdapter`** — Implements `DockerTerminalPort`; creates exec sessions via docker-java, manages piped I/O streams with virtual threads, handles shell fallback
- **`DockerTerminalPort`** — Port interface defining `createExecSession`, `startExecSession`, `resizeExec`, `isContainerRunning`, and `copyFileToContainer` (which creates a missing destination directory on demand and throws `DirectoryCreationException` when it cannot)
- **`TerminalSessionManager`** — Tracks active WebSocket sessions, enforces max-sessions limit, schedules hourly idle-timeout cleanup
- **`ContainerFileUploadController`** — REST endpoints for multipart file upload and image attachments; validates inputs, streams to temp file, copies to container via `copyArchiveToContainerCmd`
- **`ImageSignature`** — Detects PNG/JPEG/GIF/WebP from magic bytes so image attachments are validated by content, not by client-provided MIME type
- **`RequestStash`** — Stores terminal tickets with 5-minute TTL and automatic eviction

### Frontend

- **`ContainerTerminalDialog`** — xterm.js-based terminal with WebSocket connection, theme support, resize observer, collapsible file upload panel, and paste/drop image attachment handling
- **`terminalAttachments.ts`** — `extractImageFiles()` pulls image files out of a paste/drop `DataTransfer`; `isSupportedImage()` checks the accepted MIME types
- **`useTerminalAuth`** — Hook managing the password dialog flow, ticket acquisition, and retaining `containerId`/`password` for file upload auth
- **`terminalService.ts`** — `authorizeTerminal()` for ticket acquisition, `connectTerminal()` for WebSocket connection, `uploadFileToContainer()` and `uploadImageToContainer()` for XHR-based uploads with progress
