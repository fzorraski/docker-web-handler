# Container Logs

## Overview

Docker Web Handler provides real-time container log streaming directly in the browser. Logs are tailed via the Docker API and delivered to the UI through Server-Sent Events (SSE), giving you a live view of container output without needing terminal access.

---

## Features

- **Real-time streaming:** Logs appear as they are produced by the container
- **Last 1000 lines:** Initial load fetches the most recent 1000 lines of logs
- **Auto-scroll:** The log viewer automatically scrolls to show new entries
- **Scroll-to-bottom button:** When you scroll up to review older logs, a button appears to jump back to the latest output
- **Full-screen dialog:** Logs open in a dedicated full-screen dialog for maximum readability

---

## API

### Stream Container Logs

```
GET /api/containers/sse/logs/{containerId}
Accept: text/event-stream
```

**Events:**
- `INFO` — Log lines from the container
- `SUCCESS` — Stream ended normally (container stopped or stream closed)
- `ERROR` — Stream failure (container not found, Docker API error)

The stream remains open as long as the dialog is open, delivering new log lines in real time. Closing the dialog terminates the SSE connection.

---

## UI

1. Click the **Logs** action button on any container row in the containers table
2. A full-screen dialog opens with the log viewer
3. Logs stream in real time with auto-scroll
4. Close the dialog to stop streaming
