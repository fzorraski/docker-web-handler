package br.com.fzdevx.infrastructure.database;

import br.com.fzdevx.infrastructure.persistence.DatabaseService.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Generates a self-contained HTML report from database insights data.
 * All user-controlled content is HTML-escaped to prevent XSS.
 */
public final class DatabaseHtmlReportGenerator {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_SESSIONS = 50;
    private static final int MAX_QUERIES = 20;
    private static final int MAX_TABLES = 50;
    private static final int QUERY_TRUNCATE_LEN = 200;

    private DatabaseHtmlReportGenerator() {}

    public static String generate(String repository, String databaseName,
                                  DatabaseHealthInfo health,
                                  DatabaseActivity activity,
                                  DatabaseTableStats tableStats,
                                  ServerHealth serverHealth,
                                  List<TopQuery> tempFileQueries) {
        var sb = new StringBuilder(16384);
        htmlHead(sb, repository, databaseName);
        sectionHeader(sb, repository, databaseName);
        sectionServerOverview(sb, serverHealth);
        sectionHealth(sb, health);
        sectionSessions(sb, activity);
        sectionTopUsers(sb, activity);
        sectionBlockedProcesses(sb, activity);
        sectionTopQueries(sb, activity);
        sectionTempFileQueries(sb, tempFileQueries);
        sectionTableStats(sb, tableStats);
        sectionIndexHealth(sb, tableStats);
        htmlFoot(sb);
        return sb.toString();
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private static void sectionHeader(StringBuilder sb, String repository, String databaseName) {
        sb.append("<div class='meta'>");
        sb.append("<h1>Database Insights Report</h1>");
        sb.append("<p>Repository: <strong>").append(esc(repository)).append("</strong></p>");
        sb.append("<p>Database: <strong>").append(esc(databaseName)).append("</strong></p>");
        sb.append("<p>Generated: ").append(DT_FMT.format(Instant.now())).append("</p>");
        sb.append("</div>");
    }

    private static void sectionServerOverview(StringBuilder sb, ServerHealth server) {
        if (server == null) return;
        sb.append("<div class='section'><h2>Server Overview</h2><table><tr>");
        summaryCell(sb, "PG Version", esc(server.pgVersion()));
        summaryCell(sb, "Uptime", formatUptime(server.serverStartedAt()));
        summaryCell(sb, "Max Connections", fmt(server.maxConnections()));
        summaryCell(sb, "Current Connections", fmt(server.totalConnections()));
        summaryCell(sb, "Disk Size", fmtBytes(server.totalDiskSize()));
        sb.append("</tr></table></div>");
    }

    private static void sectionHealth(StringBuilder sb, DatabaseHealthInfo h) {
        if (h == null) return;
        sb.append("<div class='section'><h2>Health Summary</h2><table><tr>");
        summaryCell(sb, "Database Size", fmtBytes(h.sizeBytes()));
        summaryCell(sb, "Cache Hit Ratio", cacheRatioBadge(h.cacheHitRatio()));
        summaryCell(sb, "Active Connections", fmt(h.activeConnections()));
        summaryCell(sb, "Waiting Connections", h.waitingConnections() > 0
                ? "<span class='badge-warn'>" + h.waitingConnections() + "</span>"
                : String.valueOf(h.waitingConnections()));
        summaryCell(sb, "Long-Running Queries", h.longRunningQueries() > 0
                ? "<span class='badge-error'>" + h.longRunningQueries() + "</span>"
                : String.valueOf(h.longRunningQueries()));
        sb.append("</tr><tr>");
        summaryCell(sb, "Commits", fmt(h.xactCommit()));
        summaryCell(sb, "Rollbacks", rollbackBadge(h.xactCommit(), h.xactRollback()));
        summaryCell(sb, "Temp Files", h.tempFiles() > 0
                ? "<span class='badge-warn'>" + fmt(h.tempFiles()) + "</span>"
                : "0");
        summaryCell(sb, "Temp Bytes", h.tempBytes() > 0 ? fmtBytes(h.tempBytes()) : "0");
        summaryCell(sb, "Dead Tuples", deadTuplesBadge(h.deadTuples()));
        sb.append("</tr><tr>");
        summaryCell(sb, "TX ID Age", txIdAgeBadge(h.txIdAge()));
        sb.append("</tr></table></div>");
    }

    private static void sectionSessions(StringBuilder sb, DatabaseActivity activity) {
        if (activity == null || activity.sessions().isEmpty()) return;
        int total = activity.sessions().size();
        var limited = total > MAX_SESSIONS ? activity.sessions().subList(0, MAX_SESSIONS) : activity.sessions();
        sb.append("<div class='section'><h2>Active Sessions</h2><table>");
        tableHead(sb, "User", "State", "Query", "#Duration (s)", "Wait Event", "Client");
        for (var s : limited) {
            sb.append("<tr>");
            sb.append("<td>").append(esc(s.user())).append("</td>");
            sb.append("<td>").append(stateBadge(s.state())).append("</td>");
            sb.append("<td class='mono msg'>").append(esc(truncate(s.query(), QUERY_TRUNCATE_LEN))).append("</td>");
            sb.append("<td class='num'>").append(s.durationSeconds()).append("</td>");
            sb.append("<td>").append(esc(s.waitEventType() != null ? s.waitEventType() : "-")).append("</td>");
            sb.append("<td>").append(esc(s.clientAddr() != null ? s.clientAddr() : "-")).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (total > MAX_SESSIONS) {
            sb.append("<p class='truncated'>... and ").append(fmt(total - MAX_SESSIONS)).append(" more sessions not shown</p>");
        }
        sb.append("</div>");
    }

    private static void sectionTopUsers(StringBuilder sb, DatabaseActivity activity) {
        if (activity == null || activity.topUsers().isEmpty()) return;
        sb.append("<div class='section'><h2>Top Users</h2><table>");
        tableHead(sb, "User", "#Connections", "#Active", "#Idle");
        for (var u : activity.topUsers()) {
            sb.append("<tr>");
            sb.append("<td>").append(esc(u.user())).append("</td>");
            sb.append("<td class='num'>").append(u.connections()).append("</td>");
            sb.append("<td class='num'>").append(u.active()).append("</td>");
            sb.append("<td class='num'>").append(u.idle()).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }

    private static void sectionBlockedProcesses(StringBuilder sb, DatabaseActivity activity) {
        if (activity == null || activity.blockedProcesses().isEmpty()) return;
        sb.append("<div class='section'><h2>Blocked Processes</h2><table>");
        tableHead(sb, "#Blocked PID", "Blocked User", "Blocked Query", "#Blocking PID", "Blocking User", "Blocking Query", "#Waiting (s)");
        for (var bp : activity.blockedProcesses()) {
            sb.append("<tr>");
            sb.append("<td class='num'>").append(bp.blockedPid()).append("</td>");
            sb.append("<td>").append(esc(bp.blockedUser())).append("</td>");
            sb.append("<td class='mono msg'>").append(esc(truncate(bp.blockedQuery(), QUERY_TRUNCATE_LEN))).append("</td>");
            sb.append("<td class='num'>").append(bp.blockingPid()).append("</td>");
            sb.append("<td>").append(esc(bp.blockingUser())).append("</td>");
            sb.append("<td class='mono msg'>").append(esc(truncate(bp.blockingQuery(), QUERY_TRUNCATE_LEN))).append("</td>");
            sb.append("<td class='num'>").append(bp.waitingSeconds() > 30
                    ? "<span class='badge-error'>" + bp.waitingSeconds() + "</span>"
                    : String.valueOf(bp.waitingSeconds())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
    }

    private static void sectionTopQueries(StringBuilder sb, DatabaseActivity activity) {
        if (activity == null || !activity.pgStatStatementsAvailable() || activity.topQueries().isEmpty()) return;
        int total = activity.topQueries().size();
        var limited = total > MAX_QUERIES ? activity.topQueries().subList(0, MAX_QUERIES) : activity.topQueries();
        sb.append("<div class='section'><h2>Top Queries (pg_stat_statements)</h2><table>");
        tableHead(sb, "Query", "#Calls", "#Total Time", "#Mean Time", "#Rows");
        for (var q : limited) {
            sb.append("<tr>");
            sb.append("<td class='mono msg'>").append(esc(truncate(q.queryText(), QUERY_TRUNCATE_LEN))).append("</td>");
            sb.append("<td class='num'>").append(fmt(q.calls())).append("</td>");
            sb.append("<td class='num'>").append(fmtMs(Math.round(q.totalTimeMs()))).append("</td>");
            sb.append("<td class='num'>").append(fmtMs(Math.round(q.meanTimeMs()))).append("</td>");
            sb.append("<td class='num'>").append(fmt(q.rows())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (total > MAX_QUERIES) {
            sb.append("<p class='truncated'>... and ").append(fmt(total - MAX_QUERIES)).append(" more queries not shown</p>");
        }
        sb.append("</div>");
    }

    private static void sectionTempFileQueries(StringBuilder sb, List<TopQuery> tempFileQueries) {
        if (tempFileQueries == null || tempFileQueries.isEmpty()) return;
        int total = tempFileQueries.size();
        var limited = total > MAX_QUERIES ? tempFileQueries.subList(0, MAX_QUERIES) : tempFileQueries;
        sb.append("<div class='section'><h2>Top Temp-File Generating Queries</h2><table>");
        tableHead(sb, "Query", "#Calls", "#Total Time", "#Mean Time", "#Rows", "#Temp Blks Read", "#Temp Blks Written");
        for (var q : limited) {
            sb.append("<tr>");
            sb.append("<td class='mono msg'>").append(esc(truncate(q.queryText(), QUERY_TRUNCATE_LEN))).append("</td>");
            sb.append("<td class='num'>").append(fmt(q.calls())).append("</td>");
            sb.append("<td class='num'>").append(fmtMs(Math.round(q.totalTimeMs()))).append("</td>");
            sb.append("<td class='num'>").append(fmtMs(Math.round(q.meanTimeMs()))).append("</td>");
            sb.append("<td class='num'>").append(fmt(q.rows())).append("</td>");
            sb.append("<td class='num'>").append(q.tempBlksRead() > 0
                    ? "<span class='badge-warn'>" + fmt(q.tempBlksRead()) + "</span>"
                    : "0").append("</td>");
            sb.append("<td class='num'>").append(q.tempBlksWritten() > 0
                    ? "<span class='badge-warn'>" + fmt(q.tempBlksWritten()) + "</span>"
                    : "0").append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (total > MAX_QUERIES) {
            sb.append("<p class='truncated'>... and ").append(fmt(total - MAX_QUERIES)).append(" more queries not shown</p>");
        }
        sb.append("</div>");
    }

    private static void sectionTableStats(StringBuilder sb, DatabaseTableStats stats) {
        if (stats == null || stats.tables().isEmpty()) return;
        int total = stats.tables().size();
        var sorted = stats.tables().stream()
                .sorted(Comparator.comparingLong(TableStats::totalSizeBytes).reversed())
                .limit(MAX_TABLES).toList();
        sb.append("<div class='section'><h2>Table Statistics</h2><table>");
        tableHead(sb, "Table", "Schema", "#Total Size", "#Table Size", "#Index Size",
                "#Live Rows", "#Dead Rows", "#Seq Scans", "#Idx Scans", "Last Vacuum");
        for (var t : sorted) {
            sb.append("<tr>");
            sb.append("<td class='mono'>").append(esc(t.tableName())).append("</td>");
            sb.append("<td>").append(esc(t.schemaName())).append("</td>");
            sb.append("<td class='num'>").append(fmtBytes(t.totalSizeBytes())).append("</td>");
            sb.append("<td class='num'>").append(fmtBytes(t.tableSizeBytes())).append("</td>");
            sb.append("<td class='num'>").append(fmtBytes(t.indexSizeBytes())).append("</td>");
            sb.append("<td class='num'>").append(fmt(t.liveTuples())).append("</td>");
            sb.append("<td class='num'>").append(t.deadTuples() > 1000
                    ? "<span class='badge-warn'>" + fmt(t.deadTuples()) + "</span>"
                    : fmt(t.deadTuples())).append("</td>");
            sb.append("<td class='num'>").append(fmt(t.seqScan())).append("</td>");
            sb.append("<td class='num'>").append(fmt(t.idxScan())).append("</td>");
            sb.append("<td>").append(esc(coalesce(t.lastAutoVacuum(), t.lastVacuum(), "-"))).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (total > MAX_TABLES) {
            sb.append("<p class='truncated'>... and ").append(fmt(total - MAX_TABLES)).append(" more tables not shown</p>");
        }
        sb.append("</div>");
    }

    private static void sectionIndexHealth(StringBuilder sb, DatabaseTableStats stats) {
        if (stats == null) return;
        int unusedCount = stats.unusedIndexes().size();
        long wastedBytes = stats.indexImpact().stream().mapToLong(IndexImpact::wastedBytes).sum();

        sb.append("<div class='section'><h2>Index Health</h2><table><tr>");
        summaryCell(sb, "Unused Indexes", unusedCount > 0
                ? "<span class='badge-warn'>" + fmt(unusedCount) + "</span>" : "0");
        summaryCell(sb, "Wasted Space", wastedBytes > 0
                ? "<span class='badge-warn'>" + fmtBytes(wastedBytes) + "</span>" : "0 B");
        summaryCell(sb, "Stats Reset", esc(stats.statsResetAt() != null ? stats.statsResetAt() : "Never"));
        sb.append("</tr></table>");

        // Unused indexes table — all entries, no limit
        if (!stats.unusedIndexes().isEmpty()) {
            sb.append("<h3 style='margin-top:16px'>Unused Indexes</h3><table>");
            tableHead(sb, "Index", "Table", "Schema", "#Size", "Primary", "Unique");
            for (var idx : stats.unusedIndexes()) {
                sb.append("<tr>");
                sb.append("<td class='mono'>").append(esc(idx.indexName())).append("</td>");
                sb.append("<td class='mono'>").append(esc(idx.tableName())).append("</td>");
                sb.append("<td>").append(esc(idx.schemaName())).append("</td>");
                sb.append("<td class='num'>").append(fmtBytes(idx.sizeBytes())).append("</td>");
                sb.append("<td>").append(idx.isPrimary() ? "Yes" : "No").append("</td>");
                sb.append("<td>").append(idx.isUnique() ? "Yes" : "No").append("</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        // Index impact table
        if (!stats.indexImpact().isEmpty()) {
            sb.append("<h3 style='margin-top:16px'>Index Impact by Table</h3><table>");
            tableHead(sb, "Table", "#Unused Indexes", "#Wasted Bytes", "#Total Writes", "#Seq Scans", "#Idx Scans");
            for (var imp : stats.indexImpact()) {
                sb.append("<tr>");
                sb.append("<td class='mono'>").append(esc(imp.tableName())).append("</td>");
                sb.append("<td class='num'>").append(imp.unusedIndexes()).append("</td>");
                sb.append("<td class='num'>").append(fmtBytes(imp.wastedBytes())).append("</td>");
                sb.append("<td class='num'>").append(fmt(imp.totalWrites())).append("</td>");
                sb.append("<td class='num'>").append(fmt(imp.seqScans())).append("</td>");
                sb.append("<td class='num'>").append(fmt(imp.idxScans())).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("</div>");
    }

    // ── Badge helpers ───────────────────────────────────────────────────────

    private static String cacheRatioBadge(double ratio) {
        if (ratio >= 90) return "<span class='badge-success'>" + String.format("%.1f%%", ratio) + "</span>";
        if (ratio >= 70) return "<span class='badge-warn'>" + String.format("%.1f%%", ratio) + "</span>";
        return "<span class='badge-error'>" + String.format("%.1f%%", ratio) + "</span>";
    }

    private static String rollbackBadge(long commits, long rollbacks) {
        if (rollbacks == 0) return "0";
        double ratio = commits + rollbacks > 0 ? (double) rollbacks / (commits + rollbacks) * 100 : 0;
        if (ratio > 5) return "<span class='badge-error'>" + fmt(rollbacks) + " (" + String.format("%.1f%%", ratio) + ")</span>";
        if (ratio > 1) return "<span class='badge-warn'>" + fmt(rollbacks) + " (" + String.format("%.1f%%", ratio) + ")</span>";
        return fmt(rollbacks);
    }

    private static String deadTuplesBadge(long deadTuples) {
        if (deadTuples > 10_000) return "<span class='badge-error'>" + fmt(deadTuples) + "</span>";
        if (deadTuples > 1_000) return "<span class='badge-warn'>" + fmt(deadTuples) + "</span>";
        return fmt(deadTuples);
    }

    private static String txIdAgeBadge(long age) {
        String display = String.format("%.1fM", age / 1_000_000.0);
        if (age > 1_000_000_000L) return "<span class='badge-error'>" + display + "</span>";
        if (age > 500_000_000L) return "<span class='badge-warn'>" + display + "</span>";
        return display;
    }

    private static String stateBadge(String state) {
        if (state == null) return "-";
        return switch (state) {
            case "active" -> "<span class='badge-success'>active</span>";
            case "idle" -> "<span class='badge-neutral'>idle</span>";
            case "idle in transaction" -> "<span class='badge-warn'>idle in tx</span>";
            default -> "<span class='badge-info'>" + esc(state) + "</span>";
        };
    }

    // ── Generic helpers ─────────────────────────────────────────────────────

    private static void tableHead(StringBuilder sb, String... cols) {
        sb.append("<thead><tr>");
        for (String c : cols) {
            if (c.startsWith("#")) {
                sb.append("<th class='num'>").append(c.substring(1)).append("</th>");
            } else {
                sb.append("<th>").append(c).append("</th>");
            }
        }
        sb.append("</tr></thead><tbody>");
    }

    private static void summaryCell(StringBuilder sb, String label, String value) {
        sb.append("<td class='summary-cell'><div class='label'>").append(label)
                .append("</div><div class='value'>").append(value).append("</div></td>");
    }

    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return (ms / 60_000) + "m " + ((ms % 60_000) / 1000) + "s";
    }

    private static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatUptime(Instant startedAt) {
        if (startedAt == null) return "-";
        long days = ChronoUnit.DAYS.between(startedAt, Instant.now());
        if (days > 0) return days + "d";
        long hours = ChronoUnit.HOURS.between(startedAt, Instant.now());
        return hours + "h";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "-";
    }

    // ── HTML boilerplate ────────────────────────────────────────────────────

    private static void htmlHead(StringBuilder sb, String repository, String databaseName) {
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Database Insights — ").append(esc(repository)).append("/").append(esc(databaseName)).append("</title>");
        sb.append("<style>").append(CSS).append("</style></head><body>");
    }

    private static void htmlFoot(StringBuilder sb) {
        sb.append("<div class='footer'>Generated by Docker Web Handler — Database Insights</div>");
        sb.append("</body></html>");
    }

    private static final String CSS = """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { background: #1a1a2e; color: #e0e0e0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 32px; max-width: 1400px; margin: 0 auto; }
            h1 { font-size: 1.6rem; margin-bottom: 4px; color: #fff; }
            h2 { font-size: 1.15rem; color: #FF6D00; margin-bottom: 12px; border-bottom: 1px solid #333; padding-bottom: 6px; }
            h3 { font-size: 0.95rem; color: #ccc; margin: 16px 0 8px; }
            .meta { margin-bottom: 28px; }
            .meta p { font-size: 0.85rem; color: #999; margin: 2px 0; }
            .section { background: #16213e; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
            table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
            th { text-align: left; padding: 8px 10px; color: #999; font-weight: 600; text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.05em; border-bottom: 1px solid #333; }
            th.num { text-align: right; }
            td { padding: 6px 10px; border-bottom: 1px solid #222; }
            tbody tr:nth-child(even) { background: rgba(255,255,255,0.02); }
            tbody tr:hover { background: rgba(255,255,255,0.05); }
            .mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; }
            .num { text-align: right; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; }
            .msg { max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .summary-cell { text-align: center; padding: 12px 16px; }
            .summary-cell .label { font-size: 0.65rem; text-transform: uppercase; letter-spacing: 0.05em; color: #999; }
            .summary-cell .value { font-size: 1.3rem; font-weight: 700; color: #fff; margin-top: 2px; }
            .badge-error { background: rgba(211,47,47,0.15); color: #f44336; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-warn { background: rgba(237,108,2,0.15); color: #ff9800; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-info { background: rgba(2,136,209,0.15); color: #29b6f6; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-success { background: rgba(46,125,50,0.15); color: #66bb6a; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-neutral { background: rgba(255,255,255,0.06); color: #999; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .truncated { color: #666; font-size: 0.78rem; margin-top: 8px; }
            .footer { text-align: center; color: #444; font-size: 0.7rem; margin-top: 40px; padding-top: 16px; border-top: 1px solid #222; }
            """;
}
