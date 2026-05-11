# Log Analyzer

## Overview

The Log Analyzer allows you to upload log files or analyze container logs directly from the browser. It parses log lines, pairs API request/response calls with timing data, tracks job executions, detects repeated failures, provides anomaly detection with correlation analysis, multi-signal system health monitoring, performance insights, orphan request tracking, user-defined custom field extraction, HTML report generation, and endpoint stats comparison — all without modifying application code.

The feature is disabled by default. Enable it with:

```properties
log.analyzer.enabled=true
```

---

## Features

### Upload & Analyze Log Files

Upload `.log`, `.txt`, or `.out` files for offline analysis through a tabbed configuration dialog:

- **Tabbed dialog:** Upload tab (file selection + preset + threshold), Analyses tab (enable/disable features), Advanced tab (regex overrides + custom fields)
- **File selection:** Select or drag-and-drop a file; the dialog shows the selected file with size and an optional label field
- **Label:** Optional name (max 50 chars) for the analysis — shown in the analysis card tooltip and reports
- **Preset selection:** Choose a parsing preset (WildFly, Quarkus, Spring Boot, Nginx, Custom)
- **Analysis options:** Toggle individual analysis features (API calls, jobs, failures, critical issues, NPE, exceptions, custom fields) with cost indicators
- **Custom regex:** Override any regex field per upload via the Advanced tab
- **Large file support:** Up to 500 MB per file (configurable)
- **Auto-eviction:** Analyses are automatically removed after 2 hours (configurable)
- **Drag-and-drop:** Drop files on the main page to open the dialog with the file pre-loaded, or drop inside the dialog

### Analyze Container Logs

Click the **Deep Analysis** button in the Container Logs Dialog footer to navigate to the Log Analyzer with the upload dialog pre-configured:

- Opens the tabbed dialog with the container source pre-selected
- User can choose a preset, adjust the slow threshold, and configure analysis options before starting
- Takes a snapshot of the logs (does not follow the live stream)
- Strips ANSI color/formatting codes automatically
- Configurable line count via `log.analyzer.container-tail` (default: 100,000)

### API Call Pairing

The analyzer detects API request/response pairs from log messages and computes response times:

- **With correlation ID:** `CustomerOrderResource/update 73938 Request = ...` pairs with `CustomerOrderResource/update 73938 Response = ...` by matching `(thread, endpoint, correlationId)`
- **Without correlation ID:** `OrderWS/getOrders Request = ...` pairs by thread using a FIFO queue — the first unmatched Request pairs with the next Response for that endpoint on the same thread
- **Single-line mode:** For log formats where each line is a complete request (e.g., Nginx access logs), the parser detects that the API call regex has no `direction` group and creates one `ApiCallPair` per matching line. Duration is extracted from the line if an optional `duration` named group is present (in seconds, converted to milliseconds). This mode produces no orphan requests since every line is self-contained.
- **Slow call detection:** Calls at or above the slow threshold (default 1000ms) are highlighted in red
- **Content search:** Search across all request/response payloads to find specific values (order numbers, user names, etc.)
- **Sortable headers:** Click column headers (Endpoint, Thread, Request Time, Duration) to sort with direction toggle

### Endpoint Statistics

Per-endpoint aggregates with sortable columns:

| Metric | Description |
|--------|-------------|
| Count | Total number of paired calls |
| Avg | Average response time |
| Min / Max | Fastest and slowest calls |
| P95 | 95th percentile response time |
| Slow | Number of calls exceeding the slow threshold |

Features:
- **Summary cards** showing total calls, weighted average, and total slow count
- **Health border** — color-coded left border per endpoint (green/yellow/orange/red)
- **Dual bar chart** — avg and P95 overlay per endpoint
- **Sort indicators** — `TableSortLabel` arrows on all columns
- **Export Stats** — download endpoint stats as JSON for comparison (icon in table header)

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

### Performance Insights

Time-bucketed analysis of API call performance over the log timeline:

- **Endpoint selection:** Analyze all endpoints or drill into a specific one
- **Time buckets:** Auto-bucketed timeline showing request count, average response time, and P95
- **Drill-down:** Click a bucket bar to see per-endpoint breakdown for that time window; right-click to open the detail dialog
- **Sortable detail dialog:** Sort endpoints by count, avg duration, or P95 within a selected time bucket
- **Cross-tab navigation:** Jump to Performance Insights from the Endpoint Stats tab or the API Calls context menu with auto-selected endpoint/timestamp
- **Brush zoom:** Drag the brush control on duration and concurrency charts to zoom into a time range

### Anomaly Detection (Experimental)

Statistical anomaly detection across multiple signal types extracted from log patterns:

- **Signal types:** Select which signal to analyze — errors, latency, GC pauses, pool issues, deadlocks, job durations, orphan requests, and more (see [Signal Types](#signal-types) below)
- **Detection methods:**
  - **Ratio (variable baseline):** Compares each bucket to a rolling baseline window; flags buckets where the ratio exceeds the threshold
  - **Z-Score:** Compares each bucket to the global mean/standard deviation; flags buckets beyond N standard deviations
- **Metrics:** Analyze by count, P95, max, or avg
- **Bucket sizes:** 5 min, 15 min, 30 min, or 1 hour
- **Threshold:** Configurable sensitivity (1.5 to 20.0)
- **Baseline window:** Number of preceding buckets used for ratio baseline (2–50)
- **Correlation detection:** When anomalies are found, the system automatically extracts all other signal types and detects correlated anomalies — showing causal chains with severity scoring and temporal overlap across signal types
- **Visualization:** Bucket timeline chart with color-coded bars (normal, elevated, anomaly) and a list of detected anomalies with baseline comparisons
- **Synchronized charts:** Anomaly and correlation charts share the same X-axis via syncId for linked zoom/pan

### System Health (Experimental)

Multi-signal timeline showing all detected signal types on a single dual-axis chart:

- **Automatic signal discovery:** Extracts all available signals from the log and displays them together
- **Dual-axis chart:** Dominant signal on the left Y-axis, secondary signals on the right — prevents high-count signals from squashing low-count ones
- **Signal toggling:** Click signal chips to hide/show individual signals
- **Duration-aware:** Signals with meaningful duration values (API_LATENCY, SLOW_QUERY, GC_PAUSE, POOL_EXHAUSTION, JOB_DURATION) display in milliseconds when using P95/max/avg metrics
- **Metrics and bucket sizes:** Same configurable options as Anomaly Detection

### Orphan Requests

Tracks API requests that were logged but never received a matching response:

- **Dashboard card:** Shows orphan request count with warning color; also shown as a chip on the API Calls tab
- **Paginated table:** Endpoint, thread, request timestamp, and payload preview
- **Filters:** Filter by endpoint or thread
- **Expandable rows:** Click to see the full request payload
- **Cross-tab navigation:** Click the orphan chip on the API Calls tab to jump directly to this tab

### Duplicate Request Detection

Identifies repeated identical API requests that may indicate client-side retries, stuck loops, or misconfigured load balancers:

- **Grouping:** Requests are grouped by endpoint and payload content — identical requests within a configurable time window are flagged as duplicates
- **Dashboard card:** Shows duplicate group count with a warning color when duplicates are found
- **Paginated table:** Each group shows the endpoint, payload preview, occurrence count, and time span
- **Expandable rows:** Click to see all individual occurrences with timestamps and threads
- **Filters:** Filter by endpoint or minimum occurrence count

### Custom Fields

User-defined regex extractors for domain-specific log patterns. See [Custom Fields](#custom-fields-1) section below for full documentation.

Features:
- **Search:** Text search across messages, thread, and group values
- **Thread filter:** Filter by thread
- **Sortable headers:** Sort by line, timestamp, thread, or any dynamic group column
- **Expandable rows:** Click to see full log message with card-style detail panel

### Critical Issues

Grouped critical log patterns with configurable burst detection:

- **Category and pattern filters:** Filter by issue category or specific pattern
- **Text search:** Search across messages, patterns, categories, and source files
- **Burst analysis:** Detect rapid concentrations of errors in configurable time windows
- **Truncated message tooltips:** Hover over truncated messages to see the full text

### Exception Analysis

Exception grouping by type and origin:

- **Type filter:** Filter by exception type
- **Text search:** Search across exception types, origins, classes, methods, and source files
- **Expandable stack traces:** Click to view full stack trace with copy button
- **Truncated message tooltips:** Hover over truncated NPE messages to see full text

### Raw Log Viewer

Themed log viewer matching the Container Logs Dialog styling:

- **Virtualized rendering:** Uses react-window for efficient display of thousands of lines
- **Level toggle chips:** Click ERROR, WARN, INFO, SEVERE, FATAL, DEBUG, TRACE chips to filter by level — SEVERE and FATAL appear as separate chips when present
- **Clickable dashboard levels:** Click any level chip on the dashboard to jump directly to the Raw Log filtered by that level
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

### Reports

Generate downloadable self-contained HTML reports from any analysis:

- **Compact Report:** Summary cards, log level distribution, top 10 endpoints (by call count), critical issues summary, top 20 exceptions, job summary
- **Complete Report:** All compact sections plus full endpoint stats, detailed critical issues (top 10 per category), NPE analysis, all exceptions, repeated failures (top 50), orphan requests (top 50), top 100 slowest API calls, custom field summaries
- **Dark theme:** Self-contained HTML with inline CSS, matching the application's dark theme
- **Access:** Report dropdown button on the dashboard with compact/complete options

### Endpoint Stats Comparison

Compare endpoint performance between two log analyses to measure optimization impact:

- **Export:** Download endpoint stats as JSON from the Endpoint Stats tab (icon in table header)
- **Compare page:** Navigate to `/compare` to import two JSON files or load from active analyses
- **Insights cards:** Total calls, weighted average duration, total slow calls, endpoints, and slowest endpoint — with percentage differences
- **Interactive table:** Side-by-side comparison with sortable columns (calls, avg, P95, deltas, percentage change, verdict)
- **Verdict badges:** "B Faster", "B Slower", "Similar" based on ±5% threshold
- **Filter chips:** Toggle visibility of Faster, Slower, Similar, New, Removed rows
- **Endpoint filter:** Autocomplete filter to focus on specific endpoints
- **Charts:** Optional side-by-side bar charts showing avg and P95 duration for top 15 endpoints
- **Downloadable report:** HTML comparison report with insights table and verdict badges — reflects current filters
- **Access:** Navigate to `/compare` (no navbar link)

---

## Presets

Each preset defines regex patterns for parsing a specific log format. All patterns use **Java named groups** (`(?<groupName>...)`).

### Available Presets

| Preset | Log Format | Example Line |
|--------|-----------|--------------|
| **WildFly** | `TIMESTAMP LEVEL [logger] (thread) message` | `2026-03-30 07:31:13,938 INFO [stdout] (default task-1) ...` |
| **Quarkus** | Same as WildFly | `2026-03-30 07:31:13,938 INFO [io.quarkus] (main) ...` |
| **Spring Boot** | `TIMESTAMP LEVEL PID --- [thread] logger : message` | `2026-03-30T07:31:13.938-03:00 INFO 12345 --- [main] c.e.App : ...` |
| **Nginx** | Combined / cache_log access log format | `10.0.0.1 - [03/May/2026:10:15:30 +0000] "GET /api/users HTTP/1.1" 200 1234 ...` |
| **Custom** | All fields blank — user fills everything | — |

### Required Named Groups

| Regex Field | Required Groups | Optional Groups |
|-------------|----------------|-----------------|
| Log line | `timestamp`, `level`, `logger`, `thread`, `message` | — |
| API call (paired) | `endpoint`, `direction` (Request\|Response), `payload` | `correlationId` |
| API call (single-line) | `endpoint` | `duration` (in seconds) |
| Job start | `jobName` | `trigger` |
| Job end | `jobName` | `result` |
| Failure | `entityId` | `reason` |

**Note:** The parser auto-detects single-line mode when the API call regex has no `direction` group. In single-line mode, each matching log line produces one complete API call pair. The Nginx preset uses this mode.

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

### Nginx Preset Details

The Nginx preset handles both **combined** and **cache_log** (labeled) access log formats:

- **Combined:** `$remote_addr - $remote_user [$time_local] "$request" $status $body_bytes_sent "$http_referer" "$http_user_agent"`
- **Cache log:** `$remote_addr - [$time_local] "$request" $status $body_bytes_sent cache=$upstream_cache_status rt=$request_time ...`

**Field mapping:**

| Nginx Field | Mapped To | Description |
|-------------|-----------|-------------|
| `$remote_addr` | `thread` | Client IP address — enables per-client analysis |
| `$time_local` | `timestamp` | Request timestamp |
| `$request` (method + path) | `logger` / `endpoint` | HTTP method and path (query params stripped) |
| `$status` | `level` | HTTP status code — treated as log level for filtering |
| Full log line after timestamp | `message` | Complete message for search and custom fields |

**Single-line API call pairing:** Since each Nginx log line contains the complete request with response status and duration, the parser uses single-line mode. If `rt=` (request time) is present, it is extracted as the duration in seconds and converted to milliseconds.

**Pre-configured custom fields:** The Nginx preset includes security-focused custom fields for detecting suspicious paths, SQL injection attempts, XSS attempts, scanner/bot user agents, cache status, and upstream response times. These can be modified or removed per upload in the Advanced tab.

---

## Signal Types

Signal types are the categories of events that the Anomaly Detection and System Health features can extract and analyze. Available types depend on what patterns are found in the log.

| Signal Type | Source | Duration? | Description |
|-------------|--------|-----------|-------------|
| `ERROR_COUNT` | Log lines with level ERROR | No | General error frequency |
| `API_LATENCY` | API call pairs | Yes (ms) | Response time of paired API calls |
| `JOB_DURATION` | Job executions | Yes (ms) | Duration of detected job executions |
| `SLOW_QUERY` | Log messages | Yes (ms) | Slow database query warnings |
| `GC_PAUSE` | Log messages | Yes (ms) | Garbage collection pause events |
| `POOL_EXHAUSTION` | Log messages | Yes (ms) | Connection pool exhaustion events |
| `POOL_LEAK` | Log messages | No | Connection pool leak detections |
| `THREAD_REJECTION` | Log messages | No | Thread pool task rejection events |
| `OOM` | Log messages | No | Out-of-memory errors |
| `DEADLOCK` | Log messages | No | Deadlock detections |
| `NPE` | Log messages | No | NullPointerException occurrences |
| `SQL_EXCEPTION` | Log messages | No | Database exception events |
| `HTTP_ERROR` | Log messages | No | HTTP error responses |
| `ORPHAN_REQUEST` | API call pairing | No | Requests without matching responses |

Duration signals display values in milliseconds when using P95/max/avg metrics. Non-duration signals use count-based analysis.

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

1. Open the upload dialog and switch to the **Advanced** tab
2. Click **+ Add Custom Field**
3. Fill in:
   - **Field Name:** Display name (e.g., `Entity Changes`)
   - **Regex Pattern:** Java regex with named groups (e.g., `Updated -> (?<entity>\w+):`)
   - **Count Only:** Toggle on to only count matches without storing details
4. Add more fields as needed, or remove with the **X** button
5. Switch to the Upload tab and start the analysis

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

Clicking a row expands to show the full log message in a card-style detail panel with an orange accent border and a copy button.

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
| **Match cap** | 10,000 per field (configurable) | Count continues but detail storage stops after the cap — see [Memory Limits](#memory-limits) |
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

### Memory Limits

Each analysis stores match details in server memory. To prevent a single noisy log from exhausting the JVM heap — especially with multiple concurrent users — each analyzer caps the number of stored detail objects. **Counts are always accurate**; only the browsable detail rows are capped.

| Property | Default | What it caps |
|----------|---------|-------------|
| `log.analyzer.critical-issues.max-matches` | `10000` | Total critical issue matches across all categories |
| `log.analyzer.custom-fields.max-matches` | `10000` | Stored matches per custom field (count-only fields unaffected) |
| `log.analyzer.npe-analysis.max-occurrences` | `5000` | NPE occurrences with stack traces |
| `log.analyzer.exception-analysis.max-occurrences` | `5000` | Exception occurrences with stack traces |

**Why these limits exist:** A 2M-line log with 500K "could not prepare statement" errors would create 500K `CriticalIssue` objects (~500 bytes each = ~250 MB). With 5 concurrent analyses, that's 1.25 GB for critical issues alone. Stack trace analyzers (NPE, Exception) are even heavier — each occurrence stores multi-line stack traces at 1-5 KB each.

The caps are a safety net, not a feature limitation. If you have 7K+ identical errors, the summary still reports the exact count — you just can't browse all 7K individually in the table (the first N are enough to understand the pattern). Increase the caps if your server has sufficient memory and you need full detail browsability.

---

## API

### Upload & Analyze

```
POST /api/logs/analyzer/upload
Content-Type: multipart/form-data

Form fields:
  files          - Log file
  label          - Optional analysis label (max 50 chars)
  preset         - Preset name (WILDFLY, QUARKUS, SPRING_BOOT, NGINX, CUSTOM)
  slowThresholdMs - Slow call threshold in ms (optional)
  options        - JSON analysis options: {"apiCalls":true,"jobs":true,...}
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
  slowThresholdMs - Slow call threshold (optional)
```

### Query Results

```
GET /api/logs/analyzer/{id}/api-calls?endpoint=&thread=&search=&sort=time&sortDir=asc&page=0&size=50
GET /api/logs/analyzer/{id}/api-stats
GET /api/logs/analyzer/{id}/lines?thread=&level=&search=&page=0&size=500
GET /api/logs/analyzer/{id}/lines/range?from=0&to=100
GET /api/logs/analyzer/{id}/threads
GET /api/logs/analyzer/{id}/endpoints
GET /api/logs/analyzer/{id}/jobs?jobName=&page=0&size=50
GET /api/logs/analyzer/{id}/jobs/filters
GET /api/logs/analyzer/{id}/failures?page=0&size=50
GET /api/logs/analyzer/{id}/orphan-requests?endpoint=&thread=&page=0&size=50
GET /api/logs/analyzer/{id}/critical-issues?category=
GET /api/logs/analyzer/{id}/critical-issues/bursts?threshold=&windowSize=
GET /api/logs/analyzer/{id}/critical-issues/bursts/{category}?page=0&size=20
GET /api/logs/analyzer/{id}/critical-issues/bursts/{category}/{burstIndex}?page=0&size=50
GET /api/logs/analyzer/{id}/npe-analysis?page=0&size=50
GET /api/logs/analyzer/{id}/npe-analysis/{origin}/occurrences?page=0&size=20
GET /api/logs/analyzer/{id}/exception-analysis?page=0&size=50
GET /api/logs/analyzer/{id}/exception-analysis/{origin}/occurrences?page=0&size=20
GET /api/logs/analyzer/{id}/custom-fields/{fieldName}?search=&thread=&sort=&sortDir=asc&page=0&size=100
GET /api/logs/analyzer/{id}/duplicate-requests?endpoint=&minCount=&page=0&size=50
```

### Performance & Anomaly Analysis

```
GET /api/logs/analyzer/{id}/performance-insights?endpoint=
GET /api/logs/analyzer/{id}/bucket-endpoints?timestamp=&endpoint=&limit=
GET /api/logs/analyzer/{id}/anomaly-detection?signalType=ERROR_COUNT&bucketSize=300&threshold=3.0&baselineWindow=8&metric=count&method=ratio
GET /api/logs/analyzer/{id}/anomaly-detection/signal-types
GET /api/logs/analyzer/{id}/system-health?bucketSize=300&metric=count
```

### Reports & Export

```
GET  /api/logs/analyzer/{id}/report/compact     - Download compact HTML report
GET  /api/logs/analyzer/{id}/report/complete     - Download complete HTML report
GET  /api/logs/analyzer/{id}/api-stats/export    - Download endpoint stats as JSON
POST /api/logs/analyzer/compare-stats            - Generate HTML comparison report
     Body: {"labelA":"...","labelB":"...","endpointsA":[...],"endpointsB":[...]}
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
2. Click **Select file** or drag-and-drop a log file — this opens the **upload dialog**
3. In the dialog:
   - **Upload tab:** See the selected file, add an optional label, choose preset and slow threshold
   - **Analyses tab:** Toggle analysis features on/off (API calls, jobs, critical issues, etc.) with processing cost indicators
   - **Advanced tab:** Override regex patterns, configure sensitive fields, add custom field extractors
4. Click **Start Analysis** to begin processing
5. After analysis, the **dashboard** shows summary cards (Total Lines, API Calls, Orphan Requests, Threads, Endpoints, Errors, Jobs, Failures, Critical Issues, Exceptions, and any custom field counts)
6. **Level distribution chips** show log level counts — click any chip to jump to the Raw Log filtered by that level (SEVERE and FATAL appear separately when present)
7. **Report button** on the dashboard provides compact and complete HTML report downloads
8. **Tabs** provide detailed views:

   **Always visible:**
   - **API Calls** — paired requests/responses with rainbow brackets, expandable JSON payloads, content search, sensitive field masking, sortable column headers, and orphan request count chip
   - **Endpoint Stats** — sortable table with response time metrics, health border, dual bar chart, summary cards, and JSON export button
   - **Performance Insights** — time-bucketed response time analysis with drill-down by endpoint and time window, brush zoom
   - **Anomaly Detection** — statistical anomaly detection with configurable signal types, methods, and correlation analysis (experimental)
   - **System Health** — multi-signal dual-axis timeline showing all detected signals over time (experimental)
   - **Raw Log** — virtualized log viewer with level chips (including SEVERE/FATAL when present), search, thread filter, word wrap, and copy

   **Conditional (shown when data exists):**
   - **Critical Issues** — grouped critical log patterns with burst detection and text search (if critical issues found)
   - **NPE Analysis** — NullPointerException grouping by origin with stack traces and truncated message tooltips (if NPEs found)
   - **Exception Analysis** — exception grouping by type/origin with stack traces and text search (if exceptions found)
   - **Jobs** — job executions with duration and trigger/result (if preset has job patterns)
   - **Failures** — repeated failures grouped by entity with detail expansion (if preset has failure pattern)
   - **Orphan Requests** — API requests without matching responses (if orphans detected)
   - **Duplicate Requests** — repeated identical API requests grouped by endpoint and payload (if duplicates detected)
   - **Custom field tabs** — one tab per custom field with matches > 0, featuring search, thread filter, and sortable columns (named after the field)
