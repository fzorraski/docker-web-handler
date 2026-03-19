# Image Management

## Overview

Docker Web Handler provides a web interface for viewing, removing, and pruning Docker images. The images page shows all local images with usage information, parent-child relationships, and last-used timestamps to help you manage disk space.

The application automatically filters its own image (`fabriciozrk/docker-web-handler`) from the listing.

---

## Features

### List Images

Displays all local Docker images in a searchable, sortable table:

| Column | Description |
|--------|-------------|
| Repository | Image repository name |
| Tag | Image tag |
| Image ID | Short image identifier |
| Created | Image creation date |
| Size | Image size (formatted) |
| In Use | Whether any container is using this image |
| Containers | Number of containers using this image |
| Parent ID | Parent image (if any) |
| Child IDs | Child images (if any) |
| Last Used | Last time a container was started from this image |

The table supports:
- **Search** by repository, tag, or image ID
- **Sorting** by any column
- **Filter** to show only unused images
- **Column visibility** toggle

### Last-Used Tracking

The application tracks when images are last used to start containers. The UI color-codes images:
- **Recently used** — used within the configured threshold
- **Unused** — not used recently
- **Never used** — no usage record

### Remove Image

Removes a single Docker image. The response indicates the result:

| State Code | Meaning |
|------------|---------|
| 1 (SUCCESS) | Image removed successfully |
| 100 (IMAGE_IN_USE) | Image is being used by one or more containers |
| 101 (IMAGE_HAS_CHILDREN) | Image has dependent child images that must be removed first |
| 0 (ERROR) | General error |

Removal is streamed via SSE with context-specific error messages.

### Prune Images

Bulk removes unused images that have been idle for a specified number of days. This helps reclaim disk space from stale images.

- **Min days slider:** 0–90 days (0 = prune all unused images regardless of age)
- **Password required:** Operations password must be provided
- Images currently in use by containers are always skipped
- Progress is streamed via SSE, showing each image being removed and space reclaimed

---

## API Endpoints

### List Images

```
GET /api/images/list
```

**Response:** Array of `DockerImage` objects.

```json
[
  {
    "repository": "myapp",
    "tag": "1.0.0",
    "imageId": "sha256:abc123...",
    "created": "2026-03-15T10:00:00",
    "size": "245 MB",
    "inUse": true,
    "containerCount": 2,
    "parentId": null,
    "childIds": [],
    "lastUsedAt": "2026-03-19T08:00:00"
  }
]
```

### Remove Image

```
POST /api/images/remove
Content-Type: application/json

{ "imageId": "sha256:abc123..." }
```

**Response:**

```json
{
  "state": 1,
  "message": "Image removed"
}
```

### Remove Image (SSE)

```
GET /api/images/sse/remove/{imageId}
Accept: text/event-stream
```

### Prune Images (Prepare)

```
POST /api/images/sse/prune/prepare
Content-Type: application/json

{
  "password": "operations-password",
  "minDays": 30
}
```

**Response:**

```json
{ "ticket": "generated-uuid" }
```

### Prune Images (Stream)

```
GET /api/images/sse/prune/{ticket}
Accept: text/event-stream
```

**Events:**
- `INFO` — Analyzing images, skipping in-use images
- `PROGRESS` — Each image removed with space reclaimed
- `SUCCESS` — Pruning complete with total space reclaimed
- `ERROR` — Failure details

---

## UI

The images page is accessible at `/images`. It features a single table with inline remove buttons and a **Prune** button in the toolbar that opens a dialog with a days slider and password field.
