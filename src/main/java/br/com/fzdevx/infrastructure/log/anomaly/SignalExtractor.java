package br.com.fzdevx.infrastructure.log.anomaly;

import br.com.fzdevx.application.port.SignalExtractionPort;
import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.JobExecution;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.OrphanRequest;
import br.com.fzdevx.domain.model.anomaly.Signal;
import br.com.fzdevx.domain.model.anomaly.SignalType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SignalExtractor implements SignalExtractionPort {

    private static final int MAX_SIGNALS_PER_TYPE = 50_000;

    private record PatternDef(SignalType type, String preFilter, Pattern pattern, boolean extractNumeric) {}

    private static final List<PatternDef> PATTERNS = List.of(
            new PatternDef(SignalType.SLOW_QUERY, "Query took",
                    Pattern.compile("Query took (\\d+)ms"), true),
            new PatternDef(SignalType.GC_PAUSE, "Pause",
                    Pattern.compile("(?:Pause Full.*?(\\d+)ms|GC\\(\\d+\\) Pause.*?(\\d+)ms)"), true),
            new PatternDef(SignalType.POOL_EXHAUSTION, "Connection is not available",
                    Pattern.compile("Connection is not available.*?after (\\d+)ms"), true),
            new PatternDef(SignalType.POOL_LEAK, "Connection leak detection triggered",
                    Pattern.compile("Connection leak detection triggered"), false),
            new PatternDef(SignalType.NPE, "NullPointerException",
                    Pattern.compile("NullPointerException"), false),
            new PatternDef(SignalType.SQL_EXCEPTION, "SQLException",
                    Pattern.compile("SQLException"), false),
            new PatternDef(SignalType.HTTP_ERROR, "HTTP",
                    Pattern.compile("HTTP (\\d{3})"), true),
            new PatternDef(SignalType.THREAD_REJECTION, "RejectedExecutionException",
                    Pattern.compile("RejectedExecutionException"), false),
            new PatternDef(SignalType.OOM, "OutOfMemoryError",
                    Pattern.compile("OutOfMemoryError"), false),
            new PatternDef(SignalType.DEADLOCK, "deadlock",
                    Pattern.compile("(?i)Deadlock|deadlock found"), false)
    );

    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "SEVERE", "FATAL");

    /**
     * Extract signals of a specific type from log lines.
     */
    public List<Signal> extract(List<LogLine> lines, SignalType type) {
        return extract(lines, type, List.of());
    }

    public List<Signal> extract(List<LogLine> lines, SignalType type, List<ApiCallPair> apiCalls) {
        return extract(lines, type, apiCalls, List.of());
    }

    public List<Signal> extract(List<LogLine> lines, SignalType type, List<ApiCallPair> apiCalls,
                                List<OrphanRequest> orphans) {
        return extract(lines, type, apiCalls, List.of(), orphans);
    }

    public List<Signal> extract(List<LogLine> lines, SignalType type, List<ApiCallPair> apiCalls,
                                List<JobExecution> jobs, List<OrphanRequest> orphans) {
        if (type == SignalType.API_LATENCY) {
            return extractApiLatency(apiCalls);
        }
        if (type == SignalType.JOB_DURATION) {
            return extractJobDuration(jobs);
        }
        if (type == SignalType.ORPHAN_REQUEST) {
            return extractOrphanRequests(orphans);
        }

        if (lines == null || lines.isEmpty()) return List.of();

        if (type == SignalType.ERROR_COUNT) {
            return extractErrorCount(lines);
        }

        PatternDef def = PATTERNS.stream()
                .filter(p -> p.type() == type)
                .findFirst()
                .orElse(null);

        if (def == null) return List.of();
        return extractByPattern(lines, def);
    }

    private List<Signal> extractApiLatency(List<ApiCallPair> apiCalls) {
        if (apiCalls == null || apiCalls.isEmpty()) return List.of();
        List<Signal> signals = new ArrayList<>();
        for (ApiCallPair call : apiCalls) {
            if (call.requestTimestamp() == null) continue;
            if (signals.size() >= MAX_SIGNALS_PER_TYPE) break;
            signals.add(new Signal(
                    SignalType.API_LATENCY,
                    call.durationMs(),
                    call.endpoint() + " " + call.durationMs() + "ms",
                    call.requestTimestamp(),
                    call.thread(),
                    call.endpoint()
            ));
        }
        return signals;
    }

    private List<Signal> extractJobDuration(List<JobExecution> jobs) {
        if (jobs == null || jobs.isEmpty()) return List.of();
        List<Signal> signals = new ArrayList<>();
        for (JobExecution job : jobs) {
            if (job.startTimestamp() == null) continue;
            if (signals.size() >= MAX_SIGNALS_PER_TYPE) break;
            signals.add(new Signal(
                    SignalType.JOB_DURATION,
                    job.durationMs(),
                    job.jobName() + " " + job.durationMs() + "ms",
                    job.startTimestamp(),
                    job.thread(),
                    job.jobName()
            ));
        }
        return signals;
    }

    private List<Signal> extractOrphanRequests(List<OrphanRequest> orphans) {
        if (orphans == null || orphans.isEmpty()) return List.of();
        List<Signal> signals = new ArrayList<>();
        for (OrphanRequest orphan : orphans) {
            if (orphan.timestamp() == null) continue;
            if (signals.size() >= MAX_SIGNALS_PER_TYPE) break;
            signals.add(new Signal(
                    SignalType.ORPHAN_REQUEST,
                    null,
                    orphan.endpoint() + " (no response)",
                    orphan.timestamp(),
                    orphan.thread(),
                    orphan.endpoint()
            ));
        }
        return signals;
    }

    /**
     * Extract all signal types from log lines (for correlation detection).
     */
    public Map<SignalType, List<Signal>> extractAll(List<LogLine> lines) {
        return extractAll(lines, List.of());
    }

    public Map<SignalType, List<Signal>> extractAll(List<LogLine> lines, List<ApiCallPair> apiCalls) {
        return extractAll(lines, apiCalls, List.of());
    }

    public Map<SignalType, List<Signal>> extractAll(List<LogLine> lines, List<ApiCallPair> apiCalls,
                                                     List<OrphanRequest> orphans) {
        return extractAll(lines, apiCalls, List.of(), orphans);
    }

    public Map<SignalType, List<Signal>> extractAll(List<LogLine> lines, List<ApiCallPair> apiCalls,
                                                     List<JobExecution> jobs, List<OrphanRequest> orphans) {
        Map<SignalType, List<Signal>> result = new EnumMap<>(SignalType.class);

        if (lines != null && !lines.isEmpty()) {
            List<Signal> errorSignals = extractErrorCount(lines);
            if (!errorSignals.isEmpty()) {
                result.put(SignalType.ERROR_COUNT, errorSignals);
            }

            for (PatternDef def : PATTERNS) {
                List<Signal> signals = extractByPattern(lines, def);
                if (!signals.isEmpty()) {
                    result.put(def.type(), signals);
                }
            }
        }

        List<Signal> apiSignals = extractApiLatency(apiCalls);
        if (!apiSignals.isEmpty()) {
            result.put(SignalType.API_LATENCY, apiSignals);
        }

        List<Signal> jobSignals = extractJobDuration(jobs);
        if (!jobSignals.isEmpty()) {
            result.put(SignalType.JOB_DURATION, jobSignals);
        }

        List<Signal> orphanSignals = extractOrphanRequests(orphans);
        if (!orphanSignals.isEmpty()) {
            result.put(SignalType.ORPHAN_REQUEST, orphanSignals);
        }

        return result;
    }

    /**
     * Quick scan to detect which signal types exist in the log lines.
     * Uses pre-filter strings for fast detection without full extraction.
     */
    public List<SignalType> detectAvailableTypes(List<LogLine> lines) {
        return detectAvailableTypes(lines, List.of());
    }

    public List<SignalType> detectAvailableTypes(List<LogLine> lines, List<ApiCallPair> apiCalls) {
        return detectAvailableTypes(lines, apiCalls, List.of());
    }

    public List<SignalType> detectAvailableTypes(List<LogLine> lines, List<ApiCallPair> apiCalls,
                                                  List<OrphanRequest> orphans) {
        return detectAvailableTypes(lines, apiCalls, List.of(), orphans);
    }

    public List<SignalType> detectAvailableTypes(List<LogLine> lines, List<ApiCallPair> apiCalls,
                                                  List<JobExecution> jobs, List<OrphanRequest> orphans) {
        Set<SignalType> found = EnumSet.noneOf(SignalType.class);

        if (lines != null && !lines.isEmpty()) {
            for (LogLine line : lines) {
                if (line.level() != null && ERROR_LEVELS.contains(line.level())) {
                    found.add(SignalType.ERROR_COUNT);
                    break;
                }
            }

            Set<PatternDef> remaining = new LinkedHashSet<>(PATTERNS);
            for (LogLine line : lines) {
                if (remaining.isEmpty()) break;
                String msg = line.message();
                if (msg == null) continue;

                Iterator<PatternDef> it = remaining.iterator();
                while (it.hasNext()) {
                    PatternDef def = it.next();
                    if (containsPreFilter(msg, def)) {
                        if (def.type() == SignalType.HTTP_ERROR) {
                            Matcher m = def.pattern().matcher(msg);
                            if (m.find()) {
                                int code = Integer.parseInt(m.group(1));
                                if (code >= 400) {
                                    found.add(def.type());
                                    it.remove();
                                }
                            }
                        } else {
                            found.add(def.type());
                            it.remove();
                        }
                    }
                }
            }
        }
        if (apiCalls != null && !apiCalls.isEmpty()) {
            found.add(SignalType.API_LATENCY);
        }
        if (jobs != null && !jobs.isEmpty()) {
            found.add(SignalType.JOB_DURATION);
        }
        if (orphans != null && !orphans.isEmpty()) {
            found.add(SignalType.ORPHAN_REQUEST);
        }

        List<SignalType> result = new ArrayList<>(found);
        result.sort(Comparator.comparingInt(SignalType::ordinal));
        return result;
    }

    private List<Signal> extractErrorCount(List<LogLine> lines) {
        List<Signal> signals = new ArrayList<>();
        for (LogLine line : lines) {
            if (signals.size() >= MAX_SIGNALS_PER_TYPE) break;
            if (line.level() != null && ERROR_LEVELS.contains(line.level())) {
                signals.add(new Signal(
                        SignalType.ERROR_COUNT,
                        null,
                        line.message(),
                        line.timestamp(),
                        line.thread(),
                        line.logger()
                ));
            }
        }
        return signals;
    }

    private List<Signal> extractByPattern(List<LogLine> lines, PatternDef def) {
        List<Signal> signals = new ArrayList<>();
        for (LogLine line : lines) {
            if (signals.size() >= MAX_SIGNALS_PER_TYPE) break;
            String msg = line.message();
            if (msg == null) continue;

            // Pre-filter: fast string contains check before regex
            if (!containsPreFilter(msg, def)) continue;

            Matcher m = def.pattern().matcher(msg);
            if (m.find()) {
                Long numericValue = null;
                if (def.extractNumeric()) {
                    numericValue = extractNumericFromMatcher(m, def.type());
                }

                // For HTTP_ERROR, only include status codes >= 400
                if (def.type() == SignalType.HTTP_ERROR) {
                    if (numericValue == null || numericValue < 400) continue;
                }

                signals.add(new Signal(
                        def.type(),
                        numericValue,
                        msg,
                        line.timestamp(),
                        line.thread(),
                        line.logger()
                ));
            }
        }
        return signals;
    }

    private boolean containsPreFilter(String msg, PatternDef def) {
        // Case-insensitive for deadlock
        if (def.type() == SignalType.DEADLOCK) {
            return msg.toLowerCase().contains(def.preFilter().toLowerCase());
        }
        return msg.contains(def.preFilter());
    }

    private Long extractNumericFromMatcher(Matcher m, SignalType type) {
        // GC_PAUSE has two capturing groups (alternative patterns)
        if (type == SignalType.GC_PAUSE) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String value = g1 != null ? g1 : g2;
            return value != null ? Long.parseLong(value) : null;
        }

        if (m.groupCount() >= 1 && m.group(1) != null) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
