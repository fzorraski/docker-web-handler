package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.CriticalBurst;
import br.com.fzdevx.domain.model.CriticalIssue;
import br.com.fzdevx.domain.model.CriticalIssueSummary;
import br.com.fzdevx.domain.model.LogLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@ApplicationScoped
public class CriticalIssueDetector {

    private static final int MAX_TOTAL_MATCHES = 10_000;

    private record PatternDef(String category, String severity, String patternName, String matchString, boolean javaOnly) {}

    private static final List<PatternDef> PATTERNS = List.of(
            // ---- Generic patterns (always active) ----

            // OOM / CRITICAL
            new PatternDef("OOM", "CRITICAL", "Out of memory: Kill", "Out of memory: Kill", false),
            new PatternDef("OOM", "CRITICAL", "oom-kill", "oom-kill", false),
            new PatternDef("OOM", "CRITICAL", "Cannot allocate memory", "Cannot allocate memory", false),

            // SEGFAULT / CRITICAL
            new PatternDef("SEGFAULT", "CRITICAL", "segfault", "segfault", false),
            new PatternDef("SEGFAULT", "CRITICAL", "Segmentation fault", "Segmentation fault", false),
            new PatternDef("SEGFAULT", "CRITICAL", "SIGSEGV", "SIGSEGV", false),

            // DISK / HIGH
            new PatternDef("DISK", "HIGH", "No space left on device", "No space left on device", false),
            new PatternDef("DISK", "HIGH", "Read-only file system", "Read-only file system", false),

            // NETWORK / HIGH
            new PatternDef("NETWORK", "HIGH", "Connection timed out", "Connection timed out", false),
            new PatternDef("NETWORK", "HIGH", "ConnectTimeoutException", "ConnectTimeoutException", false),

            // NETWORK / MEDIUM
            new PatternDef("NETWORK", "MEDIUM", "Connection refused", "Connection refused", false),
            new PatternDef("NETWORK", "MEDIUM", "Read timed out", "Read timed out", false),
            new PatternDef("NETWORK", "MEDIUM", "SocketTimeoutException", "SocketTimeoutException", false),

            // ---- Java-specific patterns ----

            // OOM / CRITICAL
            new PatternDef("OOM", "CRITICAL", "OutOfMemoryError", "OutOfMemoryError", true),
            new PatternDef("OOM", "CRITICAL", "Direct buffer memory", "Direct buffer memory", true),
            new PatternDef("OOM", "CRITICAL", "GC overhead limit exceeded", "GC overhead limit exceeded", true),
            new PatternDef("OOM", "CRITICAL", "unable to create new native thread", "unable to create new native thread", true),

            // JDBC / CRITICAL
            new PatternDef("JDBC", "CRITICAL", "JDBCConnectionException", "JDBCConnectionException", true),
            new PatternDef("JDBC", "CRITICAL", "This connection has been closed", "This connection has been closed", true),
            new PatternDef("JDBC", "CRITICAL", "Unable to acquire JDBC Connection", "Unable to acquire JDBC Connection", true),
            new PatternDef("JDBC", "CRITICAL", "Cannot get a connection", "Cannot get a connection", true),
            new PatternDef("JDBC", "CRITICAL", "connection is not available", "connection is not available", true),
            new PatternDef("JDBC", "CRITICAL", "JDBC exception executing SQL", "JDBC exception executing SQL", true),
            new PatternDef("JDBC", "CRITICAL", "could not prepare statement", "could not prepare statement", true),
            new PatternDef("JDBC", "CRITICAL", "could not extract ResultSet", "could not extract ResultSet", true),
            new PatternDef("JDBC", "CRITICAL", "PSQLException", "PSQLException", true),
            new PatternDef("JDBC", "CRITICAL", "Communications link failure", "Communications link failure", true),
            new PatternDef("JDBC", "CRITICAL", "Connection pool exhausted", "Connection pool exhausted", true),
            new PatternDef("JDBC", "CRITICAL", "Pool empty", "Pool empty", true),
            new PatternDef("JDBC", "CRITICAL", "too many connections", "too many connections", true),

            // NPE / HIGH
            new PatternDef("NPE", "HIGH", "NullPointerException", "NullPointerException", true),

            // THREAD / HIGH
            new PatternDef("THREAD", "HIGH", "StackOverflowError", "StackOverflowError", true),
            new PatternDef("THREAD", "HIGH", "RejectedExecutionException", "RejectedExecutionException", true),
            new PatternDef("THREAD", "HIGH", "Found one Java-level deadlock", "Found one Java-level deadlock", true),
            new PatternDef("THREAD", "HIGH", "thread pool exhausted", "thread pool exhausted", true),

            // CLASSLOADING / HIGH
            new PatternDef("CLASSLOADING", "HIGH", "ClassNotFoundException", "ClassNotFoundException", true),
            new PatternDef("CLASSLOADING", "HIGH", "NoClassDefFoundError", "NoClassDefFoundError", true),
            new PatternDef("CLASSLOADING", "HIGH", "LinkageError", "LinkageError", true),

            // TRANSACTION / HIGH
            new PatternDef("TRANSACTION", "HIGH", "TransactionReaper::check timeout", "TransactionReaper::check timeout", true),
            new PatternDef("TRANSACTION", "HIGH", "Transaction rolled back", "Transaction rolled back", true),
            new PatternDef("TRANSACTION", "HIGH", "ARJUNA012117", "ARJUNA012117", true),
            new PatternDef("TRANSACTION", "HIGH", "ARJUNA016053", "ARJUNA016053", true),

            // ---- WildFly-specific patterns (javaOnly=true) ----

            // DEPLOYMENT / CRITICAL
            new PatternDef("DEPLOYMENT", "CRITICAL", "WFLYSRV0056", "WFLYSRV0056", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "WFLYSRV0021", "WFLYSRV0021", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "WFLYSRV0153", "WFLYSRV0153", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "WFLYCTL0080", "WFLYCTL0080", true),

            // JDBC / CRITICAL (WildFly)
            new PatternDef("JDBC", "CRITICAL", "IJ000453", "IJ000453", true),
            new PatternDef("JDBC", "CRITICAL", "IJ000655", "IJ000655", true),
            new PatternDef("JDBC", "CRITICAL", "WFLYJCA0040", "WFLYJCA0040", true),
            new PatternDef("JDBC", "CRITICAL", "WFLYJCA0047", "WFLYJCA0047", true),

            // EJB / HIGH
            new PatternDef("EJB", "HIGH", "WFLYEJB0034", "WFLYEJB0034", true),
            new PatternDef("EJB", "HIGH", "WFLYEJB0228", "WFLYEJB0228", true),
            new PatternDef("EJB", "HIGH", "WFLYEJB0442", "WFLYEJB0442", true),

            // UNDERTOW / HIGH
            new PatternDef("UNDERTOW", "HIGH", "UT005023", "UT005023", true),
            new PatternDef("UNDERTOW", "HIGH", "UT000121", "UT000121", true),

            // CLUSTER / HIGH
            new PatternDef("CLUSTER", "HIGH", "ISPN000299", "ISPN000299", true),
            new PatternDef("CLUSTER", "HIGH", "ISPN000476", "ISPN000476", true),
            new PatternDef("CLUSTER", "HIGH", "WFLYCTL0348", "WFLYCTL0348", true),

            // ---- Quarkus-specific patterns (javaOnly=true) ----

            // DEPLOYMENT / CRITICAL
            new PatternDef("DEPLOYMENT", "CRITICAL", "Failed to start application", "Failed to start application", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "Quarkus augmentation failed", "Quarkus augmentation failed", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "Failed to start quarkus", "Failed to start quarkus", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "SRCFG00014", "SRCFG00014", true),

            // JDBC / CRITICAL (Quarkus)
            new PatternDef("JDBC", "CRITICAL", "Acquisition timeout", "Acquisition timeout", true),
            new PatternDef("JDBC", "CRITICAL", "AGROAL000007", "AGROAL000007", true),
            new PatternDef("JDBC", "CRITICAL", "AGROAL000001", "AGROAL000001", true),

            // REACTIVE / HIGH
            new PatternDef("REACTIVE", "HIGH", "Thread blocked", "Thread blocked", true),
            new PatternDef("REACTIVE", "HIGH", "blocking operation on a IO thread", "blocking operation on a IO thread", true),
            new PatternDef("REACTIVE", "HIGH", "CircuitBreakerOpenException", "CircuitBreakerOpenException", true),

            // ---- Spring Boot-specific patterns (javaOnly=true) ----

            // DEPLOYMENT / CRITICAL
            new PatternDef("DEPLOYMENT", "CRITICAL", "APPLICATION FAILED TO START", "APPLICATION FAILED TO START", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "Application run failed", "Application run failed", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "Unable to start web server", "Unable to start web server", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "Port already in use", "Port already in use", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "BeanCreationException", "BeanCreationException", true),
            new PatternDef("DEPLOYMENT", "CRITICAL", "circular reference", "circular reference", true),

            // JDBC / CRITICAL (Spring Boot)
            new PatternDef("JDBC", "CRITICAL", "HikariCP pool exhaustion", "Connection is not available, request timed out", true),
            new PatternDef("JDBC", "CRITICAL", "HikariCP validation failure", "Failed to validate connection", true),
            new PatternDef("JDBC", "CRITICAL", "HikariCP connection leak", "Apparent connection leak detected", true),
            new PatternDef("JDBC", "CRITICAL", "Failed to initialize pool", "Failed to initialize pool", true),
            new PatternDef("JDBC", "CRITICAL", "CannotGetJdbcConnectionException", "CannotGetJdbcConnectionException", true),

            // HEALTH / HIGH
            new PatternDef("HEALTH", "HIGH", "LivenessState changed to BROKEN", "LivenessState changed to BROKEN", true),
            new PatternDef("HEALTH", "HIGH", "ReadinessState changed to REFUSING_TRAFFIC", "ReadinessState changed to REFUSING_TRAFFIC", true),
            new PatternDef("HEALTH", "HIGH", "DataSource health check failed", "DataSource health check failed", true),

            // REACTIVE / HIGH (Spring Boot)
            new PatternDef("REACTIVE", "HIGH", "BlockingOperationError", "BlockingOperationError", true),
            new PatternDef("REACTIVE", "HIGH", "Queue is full", "Queue is full", true)
    );

    @Inject
    @ConfigProperty(name = "log.analyzer.critical-issues.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "log.analyzer.critical-issues.java-patterns", defaultValue = "true")
    boolean javaPatterns;

    @Inject
    @ConfigProperty(name = "log.analyzer.critical-issues.burst-threshold", defaultValue = "10")
    int burstThreshold;

    @Inject
    @ConfigProperty(name = "log.analyzer.critical-issues.burst-window-minutes", defaultValue = "5")
    int burstWindowMinutes;

    public List<CriticalIssueSummary> detect(List<LogLine> lines) {
        return detect(lines, List.of());
    }

    public List<CriticalIssueSummary> detect(List<LogLine> lines, List<String> exclusions) {
        if (!enabled) {
            return List.of();
        }

        List<PatternDef> activePatterns = javaPatterns
                ? PATTERNS
                : PATTERNS.stream().filter(p -> !p.javaOnly()).toList();

        if (exclusions != null && !exclusions.isEmpty()) {
            List<String> lowerExclusions = exclusions.stream()
                    .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
            activePatterns = activePatterns.stream()
                    .filter(p -> lowerExclusions.stream().noneMatch(excl ->
                            p.matchString().toLowerCase().contains(excl)
                            || p.patternName().toLowerCase().contains(excl)))
                    .toList();
        }

        // category -> list of issues
        var groups = new LinkedHashMap<String, List<CriticalIssue>>();
        // category -> severity (use highest severity found)
        var severities = new LinkedHashMap<String, String>();
        int totalMatches = 0;

        for (LogLine line : lines) {
            if (totalMatches >= MAX_TOTAL_MATCHES) {
                break;
            }

            String msg = line.message();
            if (msg == null || msg.isEmpty()) {
                continue;
            }

            // Pattern matching is intentionally case-sensitive: log messages preserve the
            // original casing from the throwing code, so exact-case matching avoids false positives.
            for (PatternDef pattern : activePatterns) {
                if (totalMatches >= MAX_TOTAL_MATCHES) {
                    break;
                }

                if (msg.contains(pattern.matchString())) {
                    CriticalIssue issue = new CriticalIssue(
                            pattern.category(),
                            pattern.severity(),
                            pattern.patternName(),
                            line.lineNumber(),
                            line.timestamp(),
                            msg,
                            line.sourceFile()
                    );

                    groups.computeIfAbsent(pattern.category(), _ -> new ArrayList<>()).add(issue);
                    severities.merge(pattern.category(), pattern.severity(),
                            (existing, incoming) -> higherSeverity(existing, incoming));
                    totalMatches++;
                    break; // one match per line is enough
                }
            }
        }

        return groups.entrySet().stream().map(entry -> {
            String category = entry.getKey();
            List<CriticalIssue> issues = entry.getValue();
            String severity = severities.get(category);

            LocalDateTime firstSeen = issues.stream()
                    .map(CriticalIssue::timestamp)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            LocalDateTime lastSeen = issues.stream()
                    .map(CriticalIssue::timestamp)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            return new CriticalIssueSummary(category, severity, issues.size(), firstSeen, lastSeen, issues, List.of());
        }).toList();
    }

    /**
     * Compute bursts on-demand using default config values.
     */
    public List<CriticalIssueSummary> computeBursts(List<CriticalIssueSummary> summaries) {
        return computeBursts(summaries, burstThreshold, burstWindowMinutes);
    }

    /**
     * Compute bursts on-demand with custom threshold and window.
     */
    public List<CriticalIssueSummary> computeBursts(List<CriticalIssueSummary> summaries, int threshold, int windowMinutes) {
        return summaries.stream().map(s -> {
            List<CriticalBurst> bursts = detectBursts(s.issues(), s.category(), s.severity(), threshold, windowMinutes);
            return new CriticalIssueSummary(s.category(), s.severity(), s.count(), s.firstSeen(), s.lastSeen(), s.issues(), bursts);
        }).toList();
    }

    private List<CriticalBurst> detectBursts(List<CriticalIssue> issues, String category, String severity, int threshold, int windowMins) {
        List<CriticalIssue> timed = issues.stream()
                .filter(i -> i.timestamp() != null)
                .sorted(Comparator.comparing(CriticalIssue::timestamp))
                .toList();
        if (timed.size() < threshold) return List.of();

        List<CriticalBurst> bursts = new ArrayList<>();
        int i = 0;
        while (i < timed.size()) {
            LocalDateTime windowEnd = timed.get(i).timestamp().plusMinutes(windowMins);
            int j = i;
            while (j < timed.size() && !timed.get(j).timestamp().isAfter(windowEnd)) j++;
            if (j - i >= threshold) {
                bursts.add(new CriticalBurst(category, severity,
                        timed.get(i).timestamp(), timed.get(j - 1).timestamp(),
                        j - i, timed.subList(i, j)));
                i = j; // skip past this burst
            } else {
                i++;
            }
        }
        // Merge adjacent bursts if gap < windowMins
        if (bursts.size() <= 1) return bursts;
        List<CriticalBurst> merged = new ArrayList<>();
        CriticalBurst current = bursts.get(0);
        for (int k = 1; k < bursts.size(); k++) {
            CriticalBurst next = bursts.get(k);
            if (current.burstEnd() != null && next.burstStart() != null
                    && Duration.between(current.burstEnd(), next.burstStart()).toMinutes() <= windowMins) {
                var combinedIssues = new ArrayList<>(current.issues());
                combinedIssues.addAll(next.issues());
                current = new CriticalBurst(category, severity,
                        current.burstStart(), next.burstEnd(),
                        combinedIssues.size(), combinedIssues);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static String higherSeverity(String a, String b) {
        return severityRank(a) >= severityRank(b) ? a : b;
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "MEDIUM" -> 1;
            default -> 0;
        };
    }
}
