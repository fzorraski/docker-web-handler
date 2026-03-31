# Log Analyzer

## Overview

The Log Analyzer allows you to upload log files or analyze container logs directly from the browser. It parses log lines, pairs API request/response calls with timing data, tracks job executions, detects repeated failures, and supports user-defined custom field extraction — all without modifying application code.

The feature is disabled by default. Enable it with:

```properties
log.analyzer.enabled=true
```

---

## Features

### Upload & Analyze Log Files

Upload `.log`, `.txt`, or `.out` files for offline analysis:

- **Multiple files:** Upload up to 5 files simultaneously
- **Large file support:** Up to 500 MB per file (configurable)
- **Preset selection:** Choose a parsing preset (WildFly, Quarkus, Spring Boot, Custom)
- **Custom regex:** Override any regex field per upload via the Advanced section
- **Auto-eviction:** Analyses are automatically removed after 2 hours (configurable)

### Analyze Container Logs

Click the **Deep Analysis** button in the Container Logs Dialog footer to send the current container's logs directly to the analyzer:

- Takes a snapshot of the logs (does not follow the live stream)
- Strips ANSI color/formatting codes automatically
- Configurable line count via `log.analyzer.container-tail` (default: 100,000)
- Navigates to the Log Analyzer page with the analysis pre-selected

### API Call Pairing

The analyzer detects API request/response pairs from log messages and computes response times:

- **With correlation ID:** `CustomerOrderResource/update 73938 Request = ...` pairs with `CustomerOrderResource/update 73938 Response = ...` by matching `(thread, endpoint, correlationId)`
- **Without correlation ID:** `OrderWS/getOrders Request = ...` pairs by thread using a FIFO queue — the first unmatched Request pairs with the next Response for that endpoint on the same thread
- **Slow call detection:** Calls at or above the slow threshold (default 1000ms) are highlighted in red
- **Content search:** Search across all request/response payloads to find specific values (order numbers, user names, etc.)

### Endpoint Statistics

Per-endpoint aggregates with sortable columns:

| Metric | Description |
|--------|-------------|
| Count | Total number of paired calls |
| Avg | Average response time |
| Min / Max | Fastest and slowest calls |
| P95 | 95th percentile response time |
| Slow | Number of calls exceeding the slow threshold |

### Job Tracking

Detects job lifecycle events (start/end) using configurable regex patterns. The WildFly preset detects Quartz scheduler jobs by default:

- Start: `Job [JobName] vai ser disparado pelo trigger [TriggerName]`
- End: `Job [JobName] executou em ... and reports: result`

Computes job duration and displays trigger name and result.

### Repeated Failure Detection

Groups recurring failures by entity ID and reason. The WildFly preset detects patterns like:

```
ORDEM ORDER 252730 FALHA AO INICIAR UNSUFFICIENT_AMOUNT: [0, 34603]
```

Shows occurrence count, first/last seen timestamps, time span, and paginated detail expansion.

### Custom Fields

User-defined regex extractors for domain-specific log patterns. See [Custom Fields](#custom-fields-1) section below for full documentation.

### Raw Log Viewer

Themed log viewer matching the Container Logs Dialog styling:

- **Virtualized rendering:** Uses react-window for efficient display of thousands of lines
- **Level toggle chips:** Click ERROR, WARN, INFO, DEBUG, TRACE chips to filter by level
- **Word wrap toggle:** Switch between `pre` (virtualized, no wrap) and `pre-wrap` (wrapped) modes
- **Search:** Debounced text search across log messages
- **Thread filter:** Filter by specific thread
- **Copy all:** Copy visible lines to clipboard
- **Line numbers:** Shown in the left gutter

### Multi-File Composition

Merge multiple uploaded analyses into a single combined analysis:

1. Select 2 or more analyses using the checkboxes
2. Click the **Compose** button
3. Lines are sorted by timestamp across all files
4. API calls, jobs, and failures are re-paired on the merged timeline

---

## Presets

Each preset defines regex patterns for parsing a specific log format. All patterns use **Java named groups** (`(?<groupName>...)`).

### Available Presets

| Preset | Log Format | Example Line |
|--------|-----------|--------------|
| **WildFly** | `TIMESTAMP LEVEL [logger] (thread) message` | `2026-03-30 07:31:13,938 INFO [stdout] (default task-1) ...` |
| **Quarkus** | Same as WildFly | `2026-03-30 07:31:13,938 INFO [io.quarkus] (main) ...` |
| **Spring Boot** | `TIMESTAMP LEVEL PID --- [thread] logger : message` | `2026-03-30T07:31:13.938-03:00 INFO 12345 --- [main] c.e.App : ...` |
| **Custom** | All fields blank — user fills everything | — |

### Required Named Groups

| Regex Field | Required Groups | Optional Groups |
|-------------|----------------|-----------------|
| Log line | `timestamp`, `level`, `logger`, `thread`, `message` | — |
| API call | `endpoint`, `direction` (Request\|Response), `payload` | `correlationId` |
| Job start | `jobName` | `trigger` |
| Job end | `jobName` | `result` |
| Failure | `entityId` | `reason` |

### Customizing Presets

Override any preset field via `application.properties`:

```properties
# Override the WildFly API call regex
log.analyzer.preset.wildfly.api-call-regex=^(?<endpoint>\\w+/\\w+)\\s+(?<direction>Request|Response)\\s+=\\s+(?<payload>.*)$
```

Or via environment variables:

```bash
LOG_ANALYZER_PRESET_WILDFLY_API_CALL_REGEX="^(?<endpoint>\\w+/\\w+)..."
```

---

## Custom Fields

Custom fields are user-defined regex extractors that run against the parsed log **message** (the text after the timestamp, level, logger, and thread have been extracted). They produce named result sets displayed as additional tabs in the analysis.

### Why Custom Fields?

Built-in analysis covers common patterns (API calls, jobs, failures), but every application has domain-specific log patterns. Custom fields let you extract and aggregate these patterns without modifying code:

- **Entity audit trails:** Track database entity updates/deletions with who made the change
- **Stock adjustments:** Extract quantity changes with item and location details
- **Business events:** Capture domain events with structured data
- **Error codes:** Count specific error patterns across the log

### How It Works

1. You define a **name**, a **regex pattern**, and a **count-only** flag
2. The regex runs against each parsed log message
3. Named groups in the regex (`(?<groupName>...)`) become **table columns** in the results
4. Matches are displayed in a dedicated tab with the field name as the title

### Configuration

#### Via `application.properties`

Format: `name|regex|countOnly` entries separated by semicolons.

```properties
# Note: backslashes must be doubled in .properties files
log.analyzer.preset.wildfly.custom-fields=Entity Changes|Updated -> (?<entity>\\w+):.*by User:.*\\[name=(?<user>\\w+)\\]|false;Stock Adjustments|GOING TO SET amount.*\\[id=(?<id>\\d+)\\].*\\[amount=(?<fromAmount>[\\d.]+)\\].*\\*{3} to \\*{3} (?<toAmount>[\\d.]+)|false
```

Each entry has three parts separated by `|`:

| Part | Description | Example |
|------|-------------|---------|
| **name** | Tab title and summary card label | `Entity Changes` |
| **regex** | Java regex applied to log messages | `Updated -> (?<entity>\w+):` |
| **countOnly** | `true` = count only; `false` = store match details | `false` |

#### Via the UI

1. Open the **Advanced** section in the upload panel
2. Click **+ Add Custom Field**
3. Fill in:
   - **Field Name:** Display name (e.g., `Entity Changes`)
   - **Regex Pattern:** Java regex with named groups (e.g., `Updated -> (?<entity>\w+):`)
   - **Count Only:** Toggle on to only count matches without storing details
4. Add more fields as needed, or remove with the **X** button
5. Upload the log file

When a preset is selected, its configured custom fields are automatically loaded into the form. You can modify or remove them before uploading.

### Named Groups and Columns

Named groups in the regex define the columns shown in the results table:

**Example regex:**
```
Updated -> (?<entity>\w+):.*changed to.*by User:.*\[name=(?<user>\w+)\]
```

**Resulting columns:**

| Line | Timestamp | Thread | entity | user |
|------|-----------|--------|--------|------|
| 13304 | 2026-03-30 11:10:33.891 | default task-1 | User | admin |
| 13383 | 2026-03-30 11:29:29.913 | default task-1 | LOSSystemProperty | admin |

The fixed columns (Line, Timestamp, Thread) are always shown. Named group columns appear after them in the order defined by the regex.

Clicking a row expands to show the full log message with a copy button.

### Examples

#### Track Entity Changes

Match pattern: `Updated -> User: [...] changed to User: [...] by User: admin`

```
Entity Changes|Updated -> (?<entity>\\w+):.*changed to.*by User:.*\\[name=(?<user>\\w+)\\]|false
```

Columns: `entity`, `user`

#### Track Entity Removals

Match pattern: `Removed -> LOSStocktakingOrder: [...] by User: admin`

```
Entity Removals|Removed -> (?<entity>\\w+):.*by User: (?<user>\\w+)|false
```

Columns: `entity`, `user`

#### Track Stock Quantity Changes

Match pattern: `GOING TO SET amount of StockUnit [...][amount=113.0000]... *** to *** 111.0000`

```
Stock Adjustments|GOING TO SET amount.*\\[id=(?<id>\\d+)\\].*\\[amount=(?<fromAmount>[\\d.]+)\\].*\\*{3} to \\*{3} (?<toAmount>[\\d.]+)|false
```

Columns: `id`, `fromAmount`, `toAmount`

#### Count Error Occurrences (Count Only)

Just count matches without storing individual details:

```
Error Codes|error code=(?<code>\\d+)|true
```

The summary card shows the count, but no tab is created since there are no detail rows.

#### Multiple Custom Fields

Combine multiple fields with semicolons:

```properties
log.analyzer.preset.wildfly.custom-fields=Entity Changes|Updated -> (?<entity>\\w+):|false;Entity Removals|Removed -> (?<entity>\\w+):|false;Stock Adjustments|GOING TO SET amount.*\\[amount=(?<from>[\\d.]+)\\].*to \\*{3} (?<to>[\\d.]+)|false
```

### Limits and Safety

| Limit | Value | Description |
|-------|-------|-------------|
| **Field name** | Max 50 chars | Alphanumeric, spaces, and hyphens only |
| **Match cap** | 10,000 per field | Count continues but detail storage stops after 10K matches |
| **Regex validation** | 2-second timeout | Each regex is tested against diverse strings to detect catastrophic backtracking |
| **Count-only fields** | No tab created | Summary card shows count; no detail tab is rendered |

Invalid field names or unsafe regex patterns are silently skipped with a server-side warning log.

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `log.analyzer.enabled` | `false` | Enable the Log Analyzer feature |
| `log.analyzer.max-file-size-mb` | `500` | Maximum upload size per file (MB) |
| `log.analyzer.max-files` | `5` | Maximum analyses kept in memory |
| `log.analyzer.file-ttl-minutes` | `120` | Auto-eviction time (minutes) |
| `log.analyzer.slow-threshold-ms` | `1000` | API call slow threshold (ms) |
| `log.analyzer.container-tail` | `10000` | Lines fetched for container analysis |
| `log.analyzer.default-preset` | `WILDFLY` | Default parsing preset |

All properties can be overridden via environment variables (dots/hyphens become underscores, all uppercase).

---

## API

### Upload & Analyze

```
POST /api/logs/analyzer/upload
Content-Type: multipart/form-data

Form fields:
  files          - One or more log files
  preset         - Preset name (WILDFLY, QUARKUS, SPRING_BOOT, CUSTOM)
  slowThresholdMs - Slow call threshold in ms (optional)
  customFields   - JSON array of custom field definitions (optional)
  logLineRegex   - Override log line regex (optional)
  apiCallRegex   - Override API call regex (optional)
  ...            - Other preset field overrides
```

### Analyze Container Logs

```
POST /api/logs/analyzer/from-container/{containerId}
Query params:
  containerName  - Container name for the filename
  lines          - Number of lines (100-100000, default: from config)
  direction      - "tail" (last N lines) or "head" (first N lines)
  preset         - Preset name (optional)
```

### Query Results

```
GET /api/logs/analyzer/{id}/api-calls?endpoint=&thread=&search=&sort=time&page=0&size=50
GET /api/logs/analyzer/{id}/api-stats
GET /api/logs/analyzer/{id}/lines?thread=&level=&search=&page=0&size=500
GET /api/logs/analyzer/{id}/threads
GET /api/logs/analyzer/{id}/endpoints
GET /api/logs/analyzer/{id}/jobs?page=0&size=50
GET /api/logs/analyzer/{id}/failures?page=0&size=50
GET /api/logs/analyzer/{id}/custom-fields/{fieldName}?page=0&size=100
```

### Management

```
GET  /api/logs/analyzer/status          - Feature status + preset list
GET  /api/logs/analyzer/list            - All active analyses
GET  /api/logs/analyzer/{id}            - Analysis summary
POST /api/logs/analyzer/compose         - Merge analyses (body: {"ids": [...], "preset": "..."})
DELETE /api/logs/analyzer/{id}          - Remove analysis
```

---

## UI

1. Navigate to the **Log Analyzer** page (visible in the navbar when the feature is enabled)
2. **Upload** a log file or click **Deep Analysis** from the Container Logs Dialog
3. Optionally select a preset, adjust the slow threshold, and add custom fields in the Advanced section
4. After upload, the **dashboard** shows summary cards (Total Lines, API Calls, Threads, Endpoints, Errors, Jobs, Failures, and any custom field counts)
5. **Tabs** provide detailed views:
   - **API Calls** — paired requests/responses with rainbow brackets, expandable JSON payloads, content search, and sensitive field masking
   - **Endpoint Stats** — sortable table with response time metrics and visual bars
   - **Raw Log** — virtualized log viewer with level chips, search, thread filter, word wrap, and copy
   - **Thread View** — select a thread to see its isolated log flow
   - **Jobs** — job executions with duration and trigger/result (if preset has job patterns)
   - **Failures** — repeated failures grouped by entity with detail expansion (if preset has failure pattern)
   - **Custom field tabs** — one tab per custom field with matches > 0 (named after the field)
