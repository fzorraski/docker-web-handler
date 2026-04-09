package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.infrastructure.util.BytesConverter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates self-contained HTML reports from a LogAnalysis.
 * Two modes: compact (summary + top issues) and complete (all tables + top API calls).
 */
public final class HtmlReportGenerator {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlReportGenerator() {}

    public static String generateCompact(LogAnalysis a) {
        var sb = new StringBuilder(8192);
        htmlHead(sb, a, "Compact");
        sectionHeader(sb, a);
        sectionSummary(sb, a);
        sectionLevelDistribution(sb, a);
        sectionEndpointStats(sb, a, 10);
        sectionCriticalIssuesSummary(sb, a);
        sectionExceptionSummary(sb, a, 20);
        sectionJobSummary(sb, a);
        htmlFoot(sb);
        return sb.toString();
    }

    public static String generateComplete(LogAnalysis a) {
        var sb = new StringBuilder(32768);
        htmlHead(sb, a, "Complete");
        sectionHeader(sb, a);
        sectionSummary(sb, a);
        sectionLevelDistribution(sb, a);
        sectionEndpointStats(sb, a, Integer.MAX_VALUE);
        sectionCriticalIssuesDetailed(sb, a);
        sectionNpeAnalysis(sb, a);
        sectionExceptionSummary(sb, a, Integer.MAX_VALUE);
        sectionJobSummary(sb, a);
        sectionRepeatedFailures(sb, a);
        sectionOrphanRequests(sb, a);
        sectionTopSlowestCalls(sb, a, 100);
        sectionCustomFields(sb, a);
        htmlFoot(sb);
        return sb.toString();
    }

    // ── Comparison Report ────────────────────────────────────────────────────

    public static String generateComparison(String labelA, String labelB,
                                             List<EndpointStats> statsA, List<EndpointStats> statsB) {
        var sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Endpoint Stats Comparison</title><style>").append(CSS).append("</style></head><body>");

        sb.append("<div class='meta'><h1>Endpoint Stats Comparison</h1>");
        sb.append("<p><strong>A:</strong> ").append(esc(labelA)).append(" &nbsp;|&nbsp; <strong>B:</strong> ").append(esc(labelB)).append("</p></div>");

        var mapA = statsA.stream().collect(Collectors.toMap(EndpointStats::endpoint, s -> s));
        var mapB = statsB.stream().collect(Collectors.toMap(EndpointStats::endpoint, s -> s));
        var allEndpoints = new TreeSet<String>();
        allEndpoints.addAll(mapA.keySet());
        allEndpoints.addAll(mapB.keySet());

        int faster = 0, slower = 0, similar = 0, newInB = 0, removedInB = 0;
        record Row(String endpoint, EndpointStats a, EndpointStats b, double deltaAvg, double deltaPct) {}
        var rows = new ArrayList<Row>();
        for (String ep : allEndpoints) {
            var sa = mapA.get(ep);
            var sba = mapB.get(ep);
            if (sa == null) { newInB++; rows.add(new Row(ep, null, sba, 0, 0)); continue; }
            if (sba == null) { removedInB++; rows.add(new Row(ep, sa, null, 0, 0)); continue; }
            double delta = sba.avgDurationMs() - sa.avgDurationMs();
            double pct = sa.avgDurationMs() > 0 ? (delta / sa.avgDurationMs()) * 100 : 0;
            if (pct < -5) faster++;
            else if (pct > 5) slower++;
            else similar++;
            rows.add(new Row(ep, sa, sba, delta, pct));
        }

        // Summary
        sb.append("<div class='section'><h2>Summary</h2><table><tr>");
        summaryCell(sb, "Endpoints", fmt(allEndpoints.size()));
        summaryCell(sb, "B Faster", "<span style='color:#4caf50'>" + faster + "</span>");
        summaryCell(sb, "B Slower", "<span style='color:#f44336'>" + slower + "</span>");
        summaryCell(sb, "Similar", fmt(similar));
        if (newInB > 0) summaryCell(sb, "New in B", fmt(newInB));
        if (removedInB > 0) summaryCell(sb, "Removed in B", fmt(removedInB));
        sb.append("</tr></table></div>");

        // Insights
        int totalCallsA = statsA.stream().mapToInt(EndpointStats::callCount).sum();
        int totalCallsB = statsB.stream().mapToInt(EndpointStats::callCount).sum();
        double weightedAvgA = totalCallsA > 0 ? statsA.stream().mapToDouble(s -> s.avgDurationMs() * s.callCount()).sum() / totalCallsA : 0;
        double weightedAvgB = totalCallsB > 0 ? statsB.stream().mapToDouble(s -> s.avgDurationMs() * s.callCount()).sum() / totalCallsB : 0;
        int totalSlowA = statsA.stream().mapToInt(EndpointStats::slowCount).sum();
        int totalSlowB = statsB.stream().mapToInt(EndpointStats::slowCount).sum();
        var slowestA = statsA.stream().max(Comparator.comparingDouble(EndpointStats::avgDurationMs)).orElse(null);
        var slowestB = statsB.stream().max(Comparator.comparingDouble(EndpointStats::avgDurationMs)).orElse(null);

        sb.append("<div class='section'><h2>Insights</h2><table>");
        sb.append("<thead><tr><th>Metric</th><th>A</th><th>B</th><th>Difference</th></tr></thead><tbody>");
        insightRow(sb, "Total Calls", fmt(totalCallsA), fmt(totalCallsB), pctDiffLabel(totalCallsA, totalCallsB, true));
        insightRow(sb, "Weighted Avg Duration", fmtMs(Math.round(weightedAvgA)), fmtMs(Math.round(weightedAvgB)), pctDiffLabel(weightedAvgA, weightedAvgB, false));
        insightRow(sb, "Total Slow Calls", fmt(totalSlowA), fmt(totalSlowB), pctDiffLabel(totalSlowA, totalSlowB, false));
        insightRow(sb, "Endpoints", fmt(statsA.size()), fmt(statsB.size()), "");
        if (slowestA != null) insightRow(sb, "Slowest in A", slowestA.endpoint(), fmtMs(Math.round(slowestA.avgDurationMs())), "");
        if (slowestB != null) insightRow(sb, "Slowest in B", slowestB.endpoint(), fmtMs(Math.round(slowestB.avgDurationMs())), "");
        sb.append("</tbody></table></div>");

        // Comparison table
        sb.append("<div class='section'><h2>Endpoint Comparison</h2><table>");
        sb.append("<thead><tr>")
          .append("<th style='text-align:left'>Endpoint</th>")
          .append("<th>A Calls</th><th>B Calls</th>")
          .append("<th class='col-sep'>A Avg</th><th>B Avg</th><th class='col-delta'>Δ Avg</th><th class='col-delta'>%</th>")
          .append("<th class='col-sep'>A P95</th><th>B P95</th><th class='col-delta'>Δ P95</th>")
          .append("<th class='col-sep'>A Slow</th><th>B Slow</th><th class='col-sep'>Verdict</th>")
          .append("</tr></thead><tbody>");

        rows.sort(Comparator.comparingDouble(Row::deltaPct));
        for (var r : rows) {
            sb.append("<tr><td class='mono'>").append(esc(r.endpoint())).append("</td>");
            if (r.a() == null) {
                sb.append("<td class='num'>-</td><td class='num'>").append(fmt(r.b().callCount())).append("</td>");
                sb.append("<td class='num col-sep'>-</td><td class='num'>").append(fmtMs(Math.round(r.b().avgDurationMs()))).append("</td>");
                sb.append("<td class='col-delta'></td><td class='col-delta'></td>");
                sb.append("<td class='num col-sep'>-</td><td class='num'>").append(fmtMs(r.b().p95DurationMs())).append("</td><td class='col-delta'></td>");
                sb.append("<td class='num col-sep'>-</td><td class='num'>").append(r.b().slowCount()).append("</td>");
                sb.append("<td class='col-sep' style='text-align:center'><span class='badge-info'>New</span></td>");
            } else if (r.b() == null) {
                sb.append("<td class='num'>").append(fmt(r.a().callCount())).append("</td><td class='num'>-</td>");
                sb.append("<td class='num col-sep'>").append(fmtMs(Math.round(r.a().avgDurationMs()))).append("</td><td class='num'>-</td>");
                sb.append("<td class='col-delta'></td><td class='col-delta'></td>");
                sb.append("<td class='num col-sep'>").append(fmtMs(r.a().p95DurationMs())).append("</td><td class='num'>-</td><td class='col-delta'></td>");
                sb.append("<td class='num col-sep'>").append(r.a().slowCount()).append("</td><td class='num'>-</td>");
                sb.append("<td class='col-sep' style='text-align:center'><span class='badge-warn'>Removed</span></td>");
            } else {
                sb.append("<td class='num'>").append(fmt(r.a().callCount())).append("</td>");
                sb.append("<td class='num'>").append(fmt(r.b().callCount())).append("</td>");
                sb.append("<td class='num col-sep'>").append(fmtMs(Math.round(r.a().avgDurationMs()))).append("</td>");
                sb.append("<td class='num'>").append(fmtMs(Math.round(r.b().avgDurationMs()))).append("</td>");
                String color = r.deltaPct() < -5 ? "#66bb6a" : r.deltaPct() > 5 ? "#f44336" : "#666";
                sb.append("<td class='num col-delta' style='color:").append(color).append("'>").append(fmtMs(Math.round(r.deltaAvg()))).append("</td>");
                sb.append("<td class='num col-delta' style='color:").append(color).append(";font-weight:700'>").append(String.format("%+.1f%%", r.deltaPct())).append("</td>");
                long deltaP95 = r.b().p95DurationMs() - r.a().p95DurationMs();
                sb.append("<td class='num col-sep'>").append(fmtMs(r.a().p95DurationMs())).append("</td>");
                sb.append("<td class='num'>").append(fmtMs(r.b().p95DurationMs())).append("</td>");
                String p95Color = deltaP95 < 0 ? "#66bb6a" : deltaP95 > 0 ? "#f44336" : "#666";
                sb.append("<td class='num col-delta' style='color:").append(p95Color).append("'>").append(fmtMs(deltaP95)).append("</td>");
                sb.append("<td class='num col-sep'>").append(r.a().slowCount()).append("</td>");
                sb.append("<td class='num'>").append(r.b().slowCount()).append("</td>");
                String verdict;
                if (r.deltaPct() < -5) verdict = "<span class='badge-success'>B Faster</span>";
                else if (r.deltaPct() > 5) verdict = "<span class='badge-error'>B Slower</span>";
                else verdict = "<span class='badge-neutral'>Similar</span>";
                sb.append("<td class='col-sep' style='text-align:center'>").append(verdict).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table></div>");
        sb.append("<div class='footer'>Generated by Docker Web Handler — Log Analyzer</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void insightRow(StringBuilder sb, String metric, String valA, String valB, String diff) {
        sb.append("<tr><td><strong>").append(metric).append("</strong></td>")
          .append("<td class='num'>").append(valA).append("</td>")
          .append("<td class='num'>").append(valB).append("</td>")
          .append("<td class='num'>").append(diff).append("</td></tr>");
    }

    private static String pctDiffLabel(double a, double b, boolean higherIsBetter) {
        if (a == 0 && b == 0) return "";
        if (a == 0) return "";
        double pct = Math.abs(((b - a) / a) * 100);
        String who = b > a ? "B" : "A";
        if (higherIsBetter) {
            return String.format("<span style='color:#999'>%s has %.1f%% more</span>", who, pct);
        } else {
            String faster = b < a ? "B" : "A";
            String color = b < a ? "#4caf50" : "#f44336";
            return String.format("<span style='color:%s'>%s is %.1f%% faster</span>", color, faster, pct);
        }
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private static void sectionHeader(StringBuilder sb, LogAnalysis a) {
        String label = a.getLabel() != null ? a.getLabel() : a.getSourceFiles().stream()
                .map(LogAnalysis.SourceFile::filename).collect(Collectors.joining(", "));
        String files = a.getSourceFiles().stream()
                .map(f -> esc(f.filename()) + " (" + BytesConverter.bytesToMegabytesFormatted(f.size(), 2) + ")")
                .collect(Collectors.joining(", "));
        sb.append("<div class='meta'>");
        sb.append("<h1>").append(esc(label)).append("</h1>");
        sb.append("<p>Files: ").append(files).append("</p>");
        sb.append("<p>Uploaded: ").append(a.getUploadedAt()).append("</p>");
        sb.append("<p>Time range: ").append(fmtDt(a.getTimeRangeStart())).append(" — ").append(fmtDt(a.getTimeRangeEnd())).append("</p>");
        sb.append("</div>");
    }

    private static void sectionSummary(StringBuilder sb, LogAnalysis a) {
        sb.append("<div class='section'><h2>Summary</h2><table><tr>");
        summaryCell(sb, "Total Lines", fmt(a.getTotalLineCount()));
        summaryCell(sb, "API Calls", fmt(a.getApiCalls().size()));
        summaryCell(sb, "Endpoints", fmt(a.getEndpoints().size()));
        summaryCell(sb, "Threads", fmt(a.getThreads().size()));
        summaryCell(sb, "Errors", fmt(a.getErrors().size()));
        summaryCell(sb, "Jobs", fmt(a.getJobExecutions().size()));
        summaryCell(sb, "Critical Issues", fmt(a.getCriticalIssues().stream().mapToInt(CriticalIssueSummary::count).sum()));
        summaryCell(sb, "Orphan Requests", fmt(a.getOrphanRequests().size()));
        sb.append("</tr></table></div>");
    }

    private static void summaryCell(StringBuilder sb, String label, String value) {
        sb.append("<td class='summary-cell'><div class='label'>").append(label)
          .append("</div><div class='value'>").append(value).append("</div></td>");
    }

    private static void sectionLevelDistribution(StringBuilder sb, LogAnalysis a) {
        if (a.getLevelCounts().isEmpty()) return;
        sb.append("<div class='section'><h2>Log Level Distribution</h2><table>");
        tableHead(sb, "Level", "Count");
        var sorted = a.getLevelCounts().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        for (var e : sorted) {
            sb.append("<tr><td>").append(levelBadge(e.getKey())).append("</td><td class='num'>")
              .append(fmt(e.getValue())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionEndpointStats(StringBuilder sb, LogAnalysis a, int limit) {
        if (a.getEndpointStats().isEmpty()) return;
        String title = limit < Integer.MAX_VALUE ? "Top " + limit + " Slowest Endpoints" : "Endpoint Stats";
        var stats = a.getEndpointStats().stream()
                .sorted(Comparator.comparingInt(EndpointStats::callCount).reversed())
                .limit(limit).toList();
        sb.append("<div class='section'><h2>").append(title).append("</h2><table>");
        tableHead(sb, "Endpoint", "Calls", "Avg", "Min", "Max", "P95", "Slow");
        for (var s : stats) {
            sb.append("<tr><td class='mono'>").append(esc(s.endpoint())).append("</td>")
              .append("<td class='num'>").append(fmt(s.callCount())).append("</td>")
              .append("<td class='num'>").append(fmtMs(Math.round(s.avgDurationMs()))).append("</td>")
              .append("<td class='num'>").append(fmtMs(s.minDurationMs())).append("</td>")
              .append("<td class='num'>").append(fmtMs(s.maxDurationMs())).append("</td>")
              .append("<td class='num'>").append(fmtMs(s.p95DurationMs())).append("</td>")
              .append("<td class='num'>").append(s.slowCount() > 0 ? "<span class='badge-error'>" + s.slowCount() + "</span>" : "0").append("</td>")
              .append("</tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionCriticalIssuesSummary(StringBuilder sb, LogAnalysis a) {
        if (a.getCriticalIssues().isEmpty()) return;
        sb.append("<div class='section'><h2>Critical Issues</h2><table>");
        tableHead(sb, "Category", "Severity", "Count", "First Seen", "Last Seen");
        for (var s : a.getCriticalIssues()) {
            sb.append("<tr><td>").append(esc(s.category())).append("</td>")
              .append("<td>").append(severityBadge(s.severity())).append("</td>")
              .append("<td class='num'>").append(fmt(s.count())).append("</td>")
              .append("<td>").append(fmtDt(s.firstSeen())).append("</td>")
              .append("<td>").append(fmtDt(s.lastSeen())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionCriticalIssuesDetailed(StringBuilder sb, LogAnalysis a) {
        if (a.getCriticalIssues().isEmpty()) return;
        sb.append("<div class='section'><h2>Critical Issues (Detailed)</h2>");
        for (var s : a.getCriticalIssues()) {
            sb.append("<h3>").append(esc(s.category())).append(" — ").append(severityBadge(s.severity()))
              .append(" (").append(fmt(s.count())).append(" issues)</h3><table>");
            tableHead(sb, "Pattern", "Line", "Timestamp", "Message");
            for (var issue : s.issues()) {
                sb.append("<tr><td class='mono'>").append(esc(issue.pattern())).append("</td>")
                  .append("<td class='num'>").append(issue.lineNumber()).append("</td>")
                  .append("<td>").append(fmtDt(issue.timestamp())).append("</td>")
                  .append("<td class='msg'>").append(esc(truncate(issue.message(), 200))).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>");
    }

    private static void sectionExceptionSummary(StringBuilder sb, LogAnalysis a, int limit) {
        if (a.getExceptionAnalysis().isEmpty()) return;
        String title = limit < Integer.MAX_VALUE ? "Exception Summary (Top " + limit + ")" : "Exception Analysis";
        var sorted = a.getExceptionAnalysis().stream()
                .sorted(Comparator.comparingInt(ExceptionLocationSummary::count).reversed())
                .limit(limit).toList();
        sb.append("<div class='section'><h2>").append(title).append("</h2><table>");
        tableHead(sb, "Exception", "Origin", "Count", "First Seen", "Last Seen");
        for (var e : sorted) {
            sb.append("<tr><td class='mono'>").append(esc(e.exceptionType())).append("</td>")
              .append("<td class='mono'>").append(esc(e.origin())).append("</td>")
              .append("<td class='num'>").append(fmt(e.count())).append("</td>")
              .append("<td>").append(fmtDt(e.firstSeen())).append("</td>")
              .append("<td>").append(fmtDt(e.lastSeen())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionNpeAnalysis(StringBuilder sb, LogAnalysis a) {
        if (a.getNpeAnalysis().isEmpty()) return;
        var sorted = a.getNpeAnalysis().stream()
                .sorted(Comparator.comparingInt(NpeLocationSummary::count).reversed()).toList();
        sb.append("<div class='section'><h2>NullPointerException Analysis</h2><table>");
        tableHead(sb, "Origin", "Count", "First Seen", "Last Seen");
        for (var n : sorted) {
            sb.append("<tr><td class='mono'>").append(esc(n.origin())).append("</td>")
              .append("<td class='num'>").append(fmt(n.count())).append("</td>")
              .append("<td>").append(fmtDt(n.firstSeen())).append("</td>")
              .append("<td>").append(fmtDt(n.lastSeen())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionJobSummary(StringBuilder sb, LogAnalysis a) {
        if (a.getJobExecutions().isEmpty()) return;
        var byName = a.getJobExecutions().stream().collect(Collectors.groupingBy(JobExecution::jobName));
        sb.append("<div class='section'><h2>Job Execution Summary</h2><table>");
        tableHead(sb, "Job", "Runs", "Avg Duration", "Failures");
        for (var e : byName.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            long avgMs = Math.round(e.getValue().stream().mapToLong(JobExecution::durationMs).average().orElse(0));
            long failures = e.getValue().stream().filter(j -> j.result() != null && j.result().toLowerCase().contains("error")).count();
            sb.append("<tr><td class='mono'>").append(esc(e.getKey())).append("</td>")
              .append("<td class='num'>").append(e.getValue().size()).append("</td>")
              .append("<td class='num'>").append(fmtMs(avgMs)).append("</td>")
              .append("<td class='num'>").append(failures > 0 ? "<span class='badge-error'>" + failures + "</span>" : "0").append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionRepeatedFailures(StringBuilder sb, LogAnalysis a) {
        if (a.getRepeatedFailures().isEmpty()) return;
        sb.append("<div class='section'><h2>Repeated Failures</h2><table>");
        tableHead(sb, "Entity", "Reason", "Occurrences", "First Seen", "Last Seen");
        for (var f : a.getRepeatedFailures()) {
            sb.append("<tr><td class='mono'>").append(esc(f.entityId())).append("</td>")
              .append("<td class='msg'>").append(esc(truncate(f.reason(), 150))).append("</td>")
              .append("<td class='num'>").append(f.occurrences()).append("</td>")
              .append("<td>").append(fmtDt(f.firstSeen())).append("</td>")
              .append("<td>").append(fmtDt(f.lastSeen())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionOrphanRequests(StringBuilder sb, LogAnalysis a) {
        if (a.getOrphanRequests().isEmpty()) return;
        sb.append("<div class='section'><h2>Orphan Requests</h2><table>");
        tableHead(sb, "Endpoint", "Thread", "Timestamp", "Line");
        for (var o : a.getOrphanRequests()) {
            sb.append("<tr><td class='mono'>").append(esc(o.endpoint())).append("</td>")
              .append("<td>").append(esc(o.thread())).append("</td>")
              .append("<td>").append(fmtDt(o.timestamp())).append("</td>")
              .append("<td class='num'>").append(o.lineNumber()).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionTopSlowestCalls(StringBuilder sb, LogAnalysis a, int limit) {
        if (a.getApiCalls().isEmpty()) return;
        var top = a.getApiCalls().stream()
                .sorted(Comparator.comparingLong(ApiCallPair::durationMs).reversed())
                .limit(limit).toList();
        sb.append("<div class='section'><h2>Top ").append(limit).append(" Slowest API Calls</h2><table>");
        tableHead(sb, "Endpoint", "Thread", "Timestamp", "Duration");
        for (var c : top) {
            sb.append("<tr><td class='mono'>").append(esc(c.endpoint())).append("</td>")
              .append("<td>").append(esc(c.thread())).append("</td>")
              .append("<td>").append(fmtDt(c.requestTimestamp())).append("</td>")
              .append("<td class='num'>").append(fmtMs(c.durationMs())).append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    private static void sectionCustomFields(StringBuilder sb, LogAnalysis a) {
        if (a.getCustomFieldResults().isEmpty()) return;
        sb.append("<div class='section'><h2>Custom Fields</h2><table>");
        tableHead(sb, "Field", "Matches", "Count Only");
        for (var cf : a.getCustomFieldResults()) {
            sb.append("<tr><td class='mono'>").append(esc(cf.fieldName())).append("</td>")
              .append("<td class='num'>").append(fmt(cf.matchCount())).append("</td>")
              .append("<td>").append(cf.countOnly() ? "Yes" : "No").append("</td></tr>");
        }
        sb.append("</table></div>");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void tableHead(StringBuilder sb, String... cols) {
        sb.append("<thead><tr>");
        for (String c : cols) sb.append("<th>").append(c).append("</th>");
        sb.append("</tr></thead><tbody>");
    }

    private static String fmtDt(LocalDateTime dt) {
        return dt != null ? dt.format(DT_FMT) : "-";
    }

    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return (ms / 60_000) + "m " + ((ms % 60_000) / 1000) + "s";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String severityBadge(String severity) {
        String cls = switch (severity) {
            case "CRITICAL" -> "badge-error";
            case "HIGH" -> "badge-warn";
            default -> "badge-info";
        };
        return "<span class='" + cls + "'>" + esc(severity) + "</span>";
    }

    private static String levelBadge(String level) {
        String cls = switch (level.toUpperCase()) {
            case "ERROR", "SEVERE", "FATAL" -> "badge-error";
            case "WARN", "WARNING" -> "badge-warn";
            default -> "badge-info";
        };
        return "<span class='" + cls + "'>" + esc(level) + "</span>";
    }

    // ── HTML boilerplate ───────────────────────────────────────────────────

    private static void htmlHead(StringBuilder sb, LogAnalysis a, String type) {
        String label = a.getLabel() != null ? a.getLabel() : a.getSourceFiles().stream()
                .map(LogAnalysis.SourceFile::filename).collect(Collectors.joining(", "));
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>Log Analysis (").append(type).append(") — ").append(esc(label)).append("</title>");
        sb.append("<style>");
        sb.append(CSS);
        sb.append("</style></head><body>");
    }

    private static void htmlFoot(StringBuilder sb) {
        sb.append("<div class='footer'>Generated by Docker Web Handler — Log Analyzer</div>");
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
            table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
            th { text-align: right; padding: 10px 12px; color: #777; font-weight: 600; text-transform: uppercase; font-size: 0.65rem; letter-spacing: 0.06em; border-bottom: 2px solid #2a2a4a; }
            th:first-child { text-align: left; }
            td { padding: 8px 12px; border-bottom: 1px solid #1e1e3a; }
            tbody tr:nth-child(even) { background: rgba(255,255,255,0.015); }
            tbody tr:hover { background: rgba(255,255,255,0.04); }
            .mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; }
            .num { text-align: right; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.78rem; }
            .msg { max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .summary-cell { text-align: center; padding: 14px 20px; }
            .summary-cell .label { font-size: 0.6rem; text-transform: uppercase; letter-spacing: 0.06em; color: #777; }
            .summary-cell .value { font-size: 1.4rem; font-weight: 700; color: #fff; margin-top: 4px; }
            .col-sep { border-left: 1px solid #2a2a4a; }
            .col-delta { background: rgba(255,255,255,0.02); }
            .badge-error { background: rgba(211,47,47,0.15); color: #f44336; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-warn { background: rgba(237,108,2,0.15); color: #ff9800; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-info { background: rgba(2,136,209,0.15); color: #29b6f6; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-success { background: rgba(46,125,50,0.15); color: #66bb6a; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .badge-neutral { background: rgba(255,255,255,0.06); color: #999; padding: 3px 10px; border-radius: 12px; font-size: 0.7rem; font-weight: 600; }
            .footer { text-align: center; color: #444; font-size: 0.7rem; margin-top: 40px; padding-top: 16px; border-top: 1px solid #222; }
            """;
}
