package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@ApplicationScoped
public class LogFileParser implements LogAnalysisPort {

    private static final Logger LOG = Logger.getLogger(LogFileParser.class.getName());
    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "SEVERE", "FATAL");
    static final int MAX_STORED_LINES = 500_000;
    private static final long REGEX_SAFETY_TIMEOUT_MS = 2000;

    @Override
    public LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs) {
        Pattern logLinePattern = compileAndValidate(preset.logLineRegex(), "logLineRegex");
        Pattern apiCallPattern = preset.apiCallRegex() != null && !preset.apiCallRegex().isBlank()
                ? compileAndValidate(preset.apiCallRegex(), "apiCallRegex") : null;
        Pattern jobStartPattern = preset.hasJobPatterns()
                ? compileAndValidate(preset.jobStartRegex(), "jobStartRegex") : null;
        Pattern jobEndPattern = preset.hasJobPatterns()
                ? compileAndValidate(preset.jobEndRegex(), "jobEndRegex") : null;
        Pattern failurePattern = preset.hasFailurePattern()
                ? compileAndValidate(preset.failureRegex(), "failureRegex") : null;
        DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern(preset.timestampFormat());

        List<LogLine> allLines = new ArrayList<>();
        List<LogAnalysis.SourceFile> sourceFiles = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String filename = filenames.get(i);
            long fileSize;
            try {
                fileSize = Files.size(file);
            } catch (IOException e) {
                fileSize = 0;
            }
            sourceFiles.add(new LogAnalysis.SourceFile(filename, fileSize));
            parseFile(file, filename, logLinePattern, timestampFormatter, allLines);
        }

        if (files.size() > 1) {
            allLines.sort(Comparator.comparing(LogLine::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        }

        List<String> sensitiveFieldNames = preset.sensitiveFieldNames() != null
                ? preset.sensitiveFieldNames() : List.of();

        List<ApiCallPair> apiCalls = apiCallPattern != null
                ? pairApiCalls(allLines, apiCallPattern, slowThresholdMs, sensitiveFieldNames)
                : List.of();

        List<JobExecution> jobExecutions = jobStartPattern != null
                ? pairJobExecutions(allLines, jobStartPattern, jobEndPattern)
                : List.of();

        List<RepeatedFailure> repeatedFailures = failurePattern != null
                ? detectRepeatedFailures(allLines, failurePattern)
                : List.of();

        Map<String, Integer> levelCounts = new LinkedHashMap<>();
        Set<String> threadSet = new LinkedHashSet<>();
        Set<String> endpointSet = new LinkedHashSet<>();
        List<LogLine> errors = new ArrayList<>();

        for (LogLine line : allLines) {
            if (line.level() != null) {
                levelCounts.merge(line.level(), 1, Integer::sum);
            }
            if (line.thread() != null) {
                threadSet.add(line.thread());
            }
            if (line.level() != null && ERROR_LEVELS.contains(line.level())) {
                errors.add(line);
            }
        }

        for (ApiCallPair pair : apiCalls) {
            endpointSet.add(pair.endpoint());
        }

        List<EndpointStats> endpointStats = computeEndpointStats(apiCalls, slowThresholdMs);

        LocalDateTime start = allLines.stream()
                .map(LogLine::timestamp)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime end = allLines.stream()
                .map(LogLine::timestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        int totalLineCount = allLines.size();
        if (allLines.size() > MAX_STORED_LINES) {
            allLines = new ArrayList<>(allLines.subList(allLines.size() - MAX_STORED_LINES, allLines.size()));
        }

        return new LogAnalysis(
                sourceFiles, totalLineCount, start, end,
                new ArrayList<>(threadSet), new ArrayList<>(endpointSet),
                apiCalls, endpointStats, levelCounts, errors,
                jobExecutions, repeatedFailures, allLines
        );
    }

    private void parseFile(Path file, String filename, Pattern logLinePattern,
                           DateTimeFormatter formatter, List<LogLine> allLines) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher m = logLinePattern.matcher(line);
                if (m.matches()) {
                    LocalDateTime timestamp = parseTimestamp(m.group("timestamp"), formatter);
                    allLines.add(new LogLine(
                            lineNumber, timestamp,
                            m.group("level"), m.group("logger"),
                            m.group("thread"), m.group("message"),
                            filename
                    ));
                } else {
                    allLines.add(new LogLine(lineNumber, null, null, null, null, line, filename));
                }
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to parse log file ''{0}'': {1}",
                    new Object[]{filename, e.getMessage()});
        }
    }

    private LocalDateTime parseTimestamp(String text, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ApiCallPair> pairApiCalls(List<LogLine> lines, Pattern apiCallPattern,
                                              int slowThresholdMs, List<String> sensitiveFieldNames) {
        // Key: thread + "|" + endpoint + "|" + correlationId (or empty)
        Map<String, Deque<PendingRequest>> pendingByCorrelation = new HashMap<>();
        // Key: thread + "|" + endpoint (for calls without correlationId — FIFO queue)
        Map<String, Deque<PendingRequest>> pendingByThreadEndpoint = new HashMap<>();
        List<ApiCallPair> pairs = new ArrayList<>();

        for (LogLine line : lines) {
            if (line.message() == null) continue;
            Matcher m = apiCallPattern.matcher(line.message());
            if (!m.matches()) continue;

            String endpoint = m.group("endpoint");
            String correlationId = safeGroup(m, "correlationId");
            String direction = m.group("direction");
            String payload = safeGroup(m, "payload");
            String thread = line.thread();

            if ("Request".equals(direction)) {
                PendingRequest pending = new PendingRequest(
                        endpoint, correlationId, thread,
                        line.timestamp(), payload, line.lineNumber(), line.sourceFile()
                );
                if (correlationId != null && !correlationId.isBlank()) {
                    String key = thread + "|" + endpoint + "|" + correlationId;
                    pendingByCorrelation.computeIfAbsent(key, _ -> new ArrayDeque<>()).add(pending);
                } else {
                    String key = thread + "|" + endpoint;
                    pendingByThreadEndpoint.computeIfAbsent(key, _ -> new ArrayDeque<>()).add(pending);
                }
            } else if ("Response".equals(direction)) {
                PendingRequest matched = null;

                if (correlationId != null && !correlationId.isBlank()) {
                    String key = thread + "|" + endpoint + "|" + correlationId;
                    Deque<PendingRequest> queue = pendingByCorrelation.get(key);
                    if (queue != null && !queue.isEmpty()) {
                        matched = queue.poll();
                    }
                }

                if (matched == null) {
                    String key = thread + "|" + endpoint;
                    Deque<PendingRequest> queue = pendingByThreadEndpoint.get(key);
                    if (queue != null && !queue.isEmpty()) {
                        matched = queue.poll();
                    }
                }

                if (matched != null && matched.timestamp != null && line.timestamp() != null) {
                    long durationMs = Duration.between(matched.timestamp, line.timestamp()).toMillis();
                    pairs.add(new ApiCallPair(
                            endpoint, matched.correlationId, thread,
                            matched.timestamp, line.timestamp(), durationMs,
                            redactPayload(matched.payload, sensitiveFieldNames),
                            redactPayload(payload, sensitiveFieldNames),
                            matched.lineNumber, line.lineNumber(),
                            matched.sourceFile, durationMs >= slowThresholdMs
                    ));
                }
            }
        }
        return pairs;
    }

    private List<JobExecution> pairJobExecutions(List<LogLine> lines, Pattern startPattern, Pattern endPattern) {
        // Key: thread + "|" + jobName
        Map<String, Deque<PendingJob>> pending = new HashMap<>();
        List<JobExecution> executions = new ArrayList<>();

        for (LogLine line : lines) {
            if (line.message() == null) continue;

            Matcher startMatcher = startPattern.matcher(line.message());
            if (startMatcher.find()) {
                String jobName = startMatcher.group("jobName");
                String trigger = safeGroup(startMatcher, "trigger");
                String key = line.thread() + "|" + jobName;
                pending.computeIfAbsent(key, _ -> new ArrayDeque<>()).add(
                        new PendingJob(jobName, trigger, line.thread(),
                                line.timestamp(), line.lineNumber(), line.sourceFile())
                );
                continue;
            }

            Matcher endMatcher = endPattern.matcher(line.message());
            if (endMatcher.find()) {
                String jobName = endMatcher.group("jobName");
                String result = safeGroup(endMatcher, "result");
                String key = line.thread() + "|" + jobName;
                Deque<PendingJob> queue = pending.get(key);
                if (queue != null && !queue.isEmpty()) {
                    PendingJob job = queue.poll();
                    long durationMs = (job.timestamp != null && line.timestamp() != null)
                            ? Duration.between(job.timestamp, line.timestamp()).toMillis() : 0;
                    executions.add(new JobExecution(
                            job.jobName, job.trigger, job.thread,
                            job.timestamp, line.timestamp(), durationMs,
                            result, job.lineNumber, line.lineNumber(), job.sourceFile
                    ));
                }
            }
        }
        return executions;
    }

    private List<RepeatedFailure> detectRepeatedFailures(List<LogLine> lines, Pattern failurePattern) {
        // Key: entityId + "|" + reason
        Map<String, List<RepeatedFailure.FailureDetail>> grouped = new LinkedHashMap<>();
        Map<String, String> entityReasonMap = new LinkedHashMap<>();

        for (LogLine line : lines) {
            if (line.message() == null) continue;
            Matcher m = failurePattern.matcher(line.message());
            if (m.find()) {
                String entityId = m.group("entityId");
                String reason = safeGroup(m, "reason");
                String key = entityId + "|" + (reason != null ? reason : "");
                entityReasonMap.putIfAbsent(key, reason);
                grouped.computeIfAbsent(key, _ -> new ArrayList<>()).add(
                        new RepeatedFailure.FailureDetail(
                                line.timestamp(), line.lineNumber(), line.message(), line.sourceFile()
                        )
                );
            }
        }

        return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> {
                    String entityId = e.getKey().split("\\|", 2)[0];
                    String reason = entityReasonMap.get(e.getKey());
                    List<RepeatedFailure.FailureDetail> details = e.getValue();
                    return new RepeatedFailure(
                            entityId, reason, details.size(),
                            details.getFirst().timestamp(),
                            details.getLast().timestamp(),
                            details
                    );
                })
                .sorted(Comparator.comparingInt(RepeatedFailure::occurrences).reversed())
                .toList();
    }

    private List<EndpointStats> computeEndpointStats(List<ApiCallPair> apiCalls, int slowThresholdMs) {
        Map<String, List<ApiCallPair>> byEndpoint = apiCalls.stream()
                .collect(Collectors.groupingBy(ApiCallPair::endpoint, LinkedHashMap::new, Collectors.toList()));

        return byEndpoint.entrySet().stream().map(e -> {
            String endpoint = e.getKey();
            List<ApiCallPair> calls = e.getValue();
            long[] durations = calls.stream().mapToLong(ApiCallPair::durationMs).sorted().toArray();
            double avg = calls.stream().mapToLong(ApiCallPair::durationMs).average().orElse(0);
            long min = durations.length > 0 ? durations[0] : 0;
            long max = durations.length > 0 ? durations[durations.length - 1] : 0;
            int p95Index = (int) Math.ceil(durations.length * 0.95) - 1;
            long p95 = durations.length > 0 ? durations[Math.max(0, p95Index)] : 0;
            int slowCount = (int) calls.stream().filter(c -> c.durationMs() >= slowThresholdMs).count();
            return new EndpointStats(endpoint, calls.size(), avg, min, max, p95, slowCount);
        })
        .sorted(Comparator.comparingInt(EndpointStats::callCount).reversed())
        .toList();
    }

    private String safeGroup(Matcher matcher, String groupName) {
        try {
            return matcher.group(groupName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Pattern compileAndValidate(String regex, String name) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new PatternSyntaxException(
                    "Invalid regex for " + name + ": " + e.getDescription(),
                    e.getPattern(), e.getIndex());
        }
        validateRegexSafety(pattern, name);
        return pattern;
    }

    private void validateRegexSafety(Pattern pattern, String name) {
        String testInput = "a".repeat(1000);
        Thread runner = new Thread(() -> pattern.matcher(testInput).find());
        runner.setDaemon(true);
        runner.start();
        try {
            runner.join(REGEX_SAFETY_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException(
                    "Regex safety check for '" + name + "' was interrupted.");
        }
        if (runner.isAlive()) {
            runner.interrupt();
            throw new IllegalArgumentException(
                    "Regex for '" + name + "' appears to be vulnerable to catastrophic backtracking "
                            + "(timed out after " + REGEX_SAFETY_TIMEOUT_MS + "ms on a 1000-char test string). "
                            + "Please simplify the pattern.");
        }
    }

    private String redactPayload(String payload, List<String> sensitiveFieldNames) {
        if (payload == null || sensitiveFieldNames == null || sensitiveFieldNames.isEmpty()) {
            return payload;
        }
        String result = payload;
        for (String fieldName : sensitiveFieldNames) {
            String escaped = Pattern.quote(fieldName);
            Pattern p = Pattern.compile("\"(" + escaped + ")\"\\s*:\\s*\"[^\"]*\"");
            result = p.matcher(result).replaceAll("\"$1\":\"***\"");
        }
        return result;
    }

    private record PendingRequest(String endpoint, String correlationId, String thread,
                                  LocalDateTime timestamp, String payload, int lineNumber, String sourceFile) {}

    private record PendingJob(String jobName, String trigger, String thread,
                              LocalDateTime timestamp, int lineNumber, String sourceFile) {}
}
