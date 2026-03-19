# Stats & Monitoring

## Overview

Docker Web Handler tracks lifetime resource usage counters and exposes them via a stats API. These counters persist across the application lifecycle and provide visibility into how the tool is being used.

---

## Available Metrics

The stats summary includes:

- **Containers created** — Total number of containers created through the application
- **Dumps uploaded** — Total number of database dumps uploaded
- **Dumps restored** — Total number of dump restore operations
- **Snapshots created** — Total number of database snapshots created
- **Migrations run** — Total number of database migration operations
- **Images pruned** — Total number of images removed via prune
- **Server started at** — Timestamp of the current server start

---

## API

### Get Stats Summary

```
GET /api/stats/summary
```

**Response:**

```json
{
  "containersCreated": 42,
  "dumpsUploaded": 15,
  "dumpsRestored": 28,
  "snapshotsCreated": 10,
  "migrationsRun": 7,
  "imagesPruned": 23,
  "startedAt": "2026-03-19T08:00:00"
}
```

---

## UI

The stats are displayed as lifetime counters in the application interface, providing a quick overview of cumulative usage.
