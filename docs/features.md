# Docker Web Handler — What the tool does for you

---

## Container Management

- Create, start, stop, and remove containers directly from the browser
- Select repository, version (tag), and configure environment variables visually
- Batch operations — select multiple containers and execute actions at once
- Search and filter by name, status, image, database
- Table customization — choose which columns to display
- View mapped ports with direct links to access the application

---

## Automatic Container Expiration

- Every container has a configurable lifespan (default: 8 hours)
- Extend the expiration at any time with a single click
- **Edit expiration** after creation: click the countdown chip or use the action menu to change the time or add an expiration
- **Enable database deletion** after creation with same validation and confirmation flow as during creation
- Cancel expiration to keep the container indefinitely
- Option to automatically delete the database when the container expires
- Conflict alert when another container is already using the same database

---

## In-Browser Terminal

- Access the container terminal directly from the browser, without needing SSH or Docker CLI
- Full screen, command history, and clickable link detection
- Upload files into the container — drag or select the file, choose the destination, and send
- Password protected

---

## Real-Time Logs

- Follow container logs in real time with automatic updates
- Filter by level: error, warning, info, debug
- Search with highlighted results
- Pause and resume reading without losing lines
- Quick navigation between errors and exceptions ("next"/"previous" buttons)
- Copy all logs or only the filtered ones
- "Deep Analysis" button that sends logs directly to the Log Analyzer

---

## Resource Monitoring

**Container:**
- CPU, memory, network (in/out), and disk — all in real time
- Visual bars with color-coded consumption (green, yellow, red)

**Server:**
- Total, used, and available memory
- CPU usage and number of cores
- Disk space

**Memory protection:**
- The tool prevents new containers from being created when the server is low on memory, avoiding instability

---

## Image Management

- List all available images with usage status
- Remove images individually or in batch
- Automatic cleanup by age — remove images not used in X days
- View occupied space and how many images are in use

---

## Database — Dumps

- Upload dump files (.sql, .dump, .gz) with description, version, and target database
- Restore dump to an existing database or automatically create a new one
- Download dumps at any time
- Storage control — storage quota with usage visualization
- Automatic expiration of old dumps
- Cleanup of dumps not used in X days
- Edit dump information (version, description, database) at any time

---

## Database — Snapshots

- Create snapshots of running databases with a single click
- Two formats: compact (lighter and faster) or readable SQL
- Restore snapshots with the same features as dumps
- Download, metadata editing, and expiration control
- Storage control separate from dumps

---

## Database — Insights & Monitoring

- **Health overview** — cache hit ratio, active connections, waiting connections, long-running queries, dead tuples, and transaction ID age
- **Activity monitoring** — active sessions, blocked processes with deadlock detection, top users by connection count
- **Table statistics** — size breakdown (data vs indexes), sequential vs index scans, dead rows, vacuum timestamps
- **Index analysis** — unused indexes with wasted space calculation, index impact per table (write overhead), index usage ratio
- **Top queries** — from `pg_stat_statements` with execution time, row counts, and temp file I/O
- **Temp file queries** — identify queries causing memory pressure by spilling to disk
- **SQL query runner** — execute read-only queries with pagination, EXPLAIN/ANALYZE support, and optional write mode
- **Statistics reset** — reset query stats (`pg_stat_statements`), table stats (`pg_stat_reset`), or individual table counters — all password-protected
- **HTML reports** — generate downloadable self-contained HTML reports with all insights for sharing
- **Database description** — add a description to any database for identification

---

## Post-Restore Scripts

- Execute SQL scripts automatically after any database restore
- **Mandatory scripts** — always executed, cannot be skipped (e.g., environment configuration, permissions)
- **Optional scripts** — the user chooses which ones to execute (e.g., test data loading, seeds)
- Execution order guaranteed by numbering (01\_init.sql, 02\_seed.sql...)
- Configurable failure behavior: stop everything or continue with the next ones

---

## Database Migration

- Automatically migrate the database version after a restore
- Two modes: paste the SQL manually or fetch from an API automatically
- SQL preview before execution — view the commands and the number of statements
- Database version control with migration history
- **Version mismatch hint**: when restoring a dump with a version older than the selected tag, an info alert suggests enabling migration

## Container Upgrade

- **Change a container's image tag** without losing its configuration — the container is recreated with the new image while preserving name, environment variables, memory, ports, expiration, and schedules
- Optionally **run a database migration** as part of the upgrade process
- Tag selector sorted by version (newest first)
- Port reuse: attempts to keep the same host ports, falls back to new allocation if unavailable
- Enabled per repository via `repository.upgrade-enabled.<repo>=true`
- Also supports **migration-only mode** (run SQL without changing the tag)

---

## Task Scheduling

- Schedule actions on containers: start, stop, create, or remove
- **One-time execution** — at a specific date and time
- **Recurring execution** — with cron expression (ready-made templates or custom)
- Visual cron assistant with human-readable translation and preview of the next 5 executions
- Enable/disable schedules without deleting them
- "Run now" button to trigger outside the scheduled time
- Execution history with status (success, failure, skipped) and message
- Conflict detection — prevents contradictory schedules on the same container

---

## Log Analyzer

### Upload and Configuration
- Drag and drop log files or analyze logs directly from a running container
- Ready-made presets for WildFly, Quarkus, Spring Boot, Nginx, or custom format
- Define what is considered "slow" (in milliseconds)
- Custom fields — define extraction patterns to search for specific information in logs
- Merge multiple analyses into a unified view

### API Calls
- Automatic request and response pairing (paired mode for application logs, single-line mode for access logs like Nginx)
- Filters by endpoint, thread, duration, and text search
- Full payload visualization
- Identification of orphan requests (without response — indicative of timeout or crash)
- **Duplicate request detection** — identifies repeated identical requests indicating retries, stuck loops, or misconfigured clients

### Per-Endpoint Statistics
- Call count, average, minimum, and maximum duration
- Performance percentiles (P50, P95, P99)
- Data export for sharing

### Raw Log Lines
- Quick navigation through millions of lines
- Filters by thread and log level
- Bookmarking important lines
- Search with highlighting and "go to line X" navigation

### Job Tracking
- Automatic identification of executed jobs
- Start and end time, result, and trigger

### Repeated Failures
- Failure grouping by type and reason
- Occurrence count with first and last appearance

### Critical Issues
- Automatic detection of severe patterns (memory overflow, connection exhaustion, etc.)
- Classification by severity: critical, high, medium
- **Burst detection** — identifies when many errors occur in a short period

### NullPointerException Analysis
- Grouping by origin location (class, method, line)
- Occurrence count and appearance period

### Exception Analysis
- All exception types grouped and counted
- Filter by type, stack trace visualization

### Custom Fields
- Extract specific information from logs using user-defined patterns
- Count mode or full capture mode

### Performance Insights
- Performance analysis by endpoint over time
- Charts with percentiles and average duration
- Interactive inspection of specific periods

### Anomaly Detection and System Health
- Signal monitoring: errors, warnings, slow calls, response time, memory and CPU usage
- Two detection methods: baseline comparison or statistical deviation
- Interactive charts with visibility control per signal

### Reports
- Compact report — summary and key findings
- Full report — detailed analysis with charts
- Comparison between two analyses — performance evolution endpoint by endpoint

---

## Performance Comparison

- Load two analyses side by side
- View response time and percentile variation by endpoint
- Filter by: new endpoints, removed, faster, slower, or unchanged
- Visual comparison chart
- Export and import data to share between teams

---

## Webhooks — Automatic Notifications

- Receive notifications when a container is created or a database is restored
- Complete information: status, repository, version, ports, database, error (if any)
- Security signature to ensure message authenticity
- Integration with any system that accepts webhooks (Slack, Teams, pipelines, etc.)

---

## CI/CD API

- Create and destroy test environments programmatically, directly from pipelines
- Health check to validate if the environment is ready
- Automatic lifespan to prevent forgotten environments
- API key authentication

---

## Security

- Password authentication with secure session
- Separate passwords for each type of operation (upload, restore, scheduling, terminal, CI)
- Sensitive operations always require password confirmation
- Protection against simultaneous access to critical operations

---

## Multi-User

- Container lock during operations — other users see that someone is operating
- Indicator of who is viewing a log analysis
- Real-time updates for all connected users

---

## Languages

- Interface available in Portuguese (Brazil), English, and Spanish
- Language switch at any time, with saved preference

---

## Visual Theme

- Light and dark mode with instant switching
- Automatically detects the operating system preference
