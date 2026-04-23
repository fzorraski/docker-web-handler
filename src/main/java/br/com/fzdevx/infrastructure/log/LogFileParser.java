package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.shared.EndpointStatsCalculator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class LogFileParser implements LogAnalysisPort {

    private static final Logger LOG = Logger.getLogger(LogFileParser.class.getName());
    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "SEVERE", "FATAL");
    @Inject
    @ConfigProperty(name = "log.analyzer.max-stored-lines", defaultValue = "500000")
    int maxStoredLines;

    @Inject
    @jakarta.inject.Named("analysisExecutor")
    ExecutorService analysisExecutor;
    private static final long REGEX_SAFETY_TIMEOUT_MS = 2000;

    @Override
    public LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                               AnalysisOptions options) {
        return analyze(files, filenames, preset, slowThresholdMs, options, e -> {}, new AtomicBoolean(false));
    }

    @Override
    public LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                               AnalysisOptions options, Consumer<ContainerEvent> progressSink, AtomicBoolean cancelled) {
        return analyze(files, filenames, preset, slowThresholdMs, options, progressSink, cancelled, null);
    }

    @Override
    public LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                               AnalysisOptions options, Consumer<ContainerEvent> progressSink, AtomicBoolean cancelled,
                               Consumer<List<LogLine>> onLinesParsed) {
        Pattern logLinePattern = compileAndValidate(preset.logLineRegex(), "logLineRegex");
        Pattern apiCallPattern = options.apiCalls() && preset.apiCallRegex() != null && !preset.apiCallRegex().isBlank()
                ? compileAndValidate(preset.apiCallRegex(), "apiCallRegex") : null;
        Pattern jobStartPattern = options.jobs() && preset.hasJobPatterns()
                ? compileAndValidate(preset.jobStartRegex(), "jobStartRegex") : null;
        Pattern jobEndPattern = options.jobs() && preset.hasJobPatterns()
                ? compileAndValidate(preset.jobEndRegex(), "jobEndRegex") : null;
        Pattern failurePattern = options.failures() && preset.hasFailurePattern()
                ? compileAndValidate(preset.failureRegex(), "failureRegex") : null;
        DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern(preset.timestampFormat());

        // Estimate total lines from file sizes (avoid reading files twice)
        long totalBytes = 0;
        for (Path file : files) {
            try { totalBytes += Files.size(file); } catch (IOException e) { /* ignore */ }
        }

        List<LogLine> allLines = new ArrayList<>();
        List<LogAnalysis.SourceFile> sourceFiles = new ArrayList<>();
        long bytesProcessed = 0;

        for (int i = 0; i < files.size(); i++) {
            checkCancelled(cancelled);
            Path file = files.get(i);
            String filename = filenames.get(i);
            long fileSize;
            try {
                fileSize = Files.size(file);
            } catch (IOException e) {
                fileSize = 0;
            }
            sourceFiles.add(new LogAnalysis.SourceFile(filename, fileSize));
            parseFile(file, filename, logLinePattern, timestampFormatter, allLines, progressSink, cancelled, bytesProcessed, totalBytes);
            bytesProcessed += fileSize;
        }

        if (files.size() > 1) {
            allLines.sort(Comparator.comparing(LogLine::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        }

        checkCancelled(cancelled);

        // Notify the caller that parsing is done — allows launching parallel work immediately
        if (onLinesParsed != null) {
            onLinesParsed.accept(Collections.unmodifiableList(allLines));
        }

        List<String> sensitiveFieldNames = preset.sensitiveFieldNames() != null
                ? preset.sensitiveFieldNames() : List.of();
        List<Map.Entry<Pattern, String>> redactionPatterns = compileRedactionPatterns(sensitiveFieldNames);

        // Phases 2-4: run in parallel — all read allLines independently
        // Capture allLines as final for lambda access (variable is reassigned later for max-stored-lines trimming)
        final List<LogLine> linesForAnalysis = allLines;

        CompletableFuture<PairingResult> apiCallsFuture = null;
        CompletableFuture<JobPairingResult> jobsFuture = null;
        CompletableFuture<List<RepeatedFailure>> failuresFuture = null;

        if (apiCallPattern != null) {
            apiCallsFuture = CompletableFuture.supplyAsync(() -> {
                progressSink.accept(ContainerEvent.info("API Calls", "Pairing API calls..."));
                var result = pairApiCalls(linesForAnalysis, apiCallPattern, slowThresholdMs, redactionPatterns, cancelled);
                progressSink.accept(ContainerEvent.info("API Calls", "Found " + result.pairs.size() + " API call pairs" +
                        (result.orphans.isEmpty() ? "" : " (" + result.orphans.size() + " orphan requests)")));
                return result;
            }, analysisExecutor);
        } else {
            progressSink.accept(ContainerEvent.info("API Calls", "Skipped"));
        }
        if (jobStartPattern != null) {
            jobsFuture = CompletableFuture.supplyAsync(() -> {
                progressSink.accept(ContainerEvent.info("Jobs", "Pairing job executions..."));
                var result = pairJobExecutions(linesForAnalysis, jobStartPattern, jobEndPattern, cancelled);
                progressSink.accept(ContainerEvent.info("Jobs", "Found " + result.executions.size() + " job executions" +
                        (result.orphans.isEmpty() ? "" : " (" + result.orphans.size() + " orphan jobs)")));
                return result;
            }, analysisExecutor);
        } else {
            progressSink.accept(ContainerEvent.info("Jobs", "Skipped"));
        }
        if (failurePattern != null) {
            failuresFuture = CompletableFuture.supplyAsync(() -> {
                progressSink.accept(ContainerEvent.info("Failures", "Detecting repeated failures..."));
                var result = detectRepeatedFailures(linesForAnalysis, failurePattern, cancelled);
                progressSink.accept(ContainerEvent.info("Failures", "Found " + result.size() + " repeated failures"));
                return result;
            }, analysisExecutor);
        } else {
            progressSink.accept(ContainerEvent.info("Failures", "Skipped"));
        }

        // Await results
        List<ApiCallPair> apiCalls = List.of();
        List<OrphanRequest> orphanRequests = List.of();
        List<JobExecution> jobExecutions = List.of();
        List<OrphanJob> orphanJobs = List.of();
        List<RepeatedFailure> repeatedFailures = List.of();

        try {
            if (apiCallsFuture != null) {
                var pairingResult = apiCallsFuture.join();
                apiCalls = pairingResult.pairs;
                orphanRequests = pairingResult.orphans;
            }
            if (jobsFuture != null) {
                var jobResult = jobsFuture.join();
                jobExecutions = jobResult.executions;
                orphanJobs = jobResult.orphans;
            }
            if (failuresFuture != null) {
                repeatedFailures = failuresFuture.join();
            }
        } catch (CompletionException e) {
            if (e.getCause() instanceof CancellationException ce) throw ce;
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(e.getCause());
        }

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

        List<EndpointStats> endpointStats = EndpointStatsCalculator.compute(apiCalls, slowThresholdMs);

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
        if (maxStoredLines > 0 && allLines.size() > maxStoredLines) {
            allLines = new ArrayList<>(allLines.subList(allLines.size() - maxStoredLines, allLines.size()));
        }

        return new LogAnalysis(
                sourceFiles, totalLineCount, start, end,
                new ArrayList<>(threadSet), new ArrayList<>(endpointSet),
                apiCalls, endpointStats, levelCounts, errors,
                jobExecutions, repeatedFailures, allLines, orphanRequests, orphanJobs
        );
    }

    private void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) throw new CancellationException("Analysis cancelled");
    }

    private void parseFile(Path file, String filename, Pattern logLinePattern,
                           DateTimeFormatter formatter, List<LogLine> allLines) {
        parseFile(file, filename, logLinePattern, formatter, allLines, e -> {}, new AtomicBoolean(false), 0, 0);
    }

    private void parseFile(Path file, String filename, Pattern logLinePattern,
                           DateTimeFormatter formatter, List<LogLine> allLines,
                           Consumer<ContainerEvent> progressSink, AtomicBoolean cancelled,
                           long bytesProcessedBefore, long totalBytes) {
        long fileSize;
        try { fileSize = Files.size(file); } catch (IOException e) { fileSize = 0; }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            int lineNumber = 0;
            int lastReported = 0;
            long bytesRead = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                bytesRead += line.length() + 1; // approximate byte count
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
                if (lineNumber - lastReported >= 2000) {
                    lastReported = lineNumber;
                    checkCancelled(cancelled);
                    int pct = totalBytes > 0
                            ? (int) ((bytesProcessedBefore + bytesRead) * 100 / totalBytes) : -1;
                    progressSink.accept(ContainerEvent.progress("Parsing",
                            "Parsing " + filename + "... " + lineNumber + " lines",
                            Math.min(pct, 99)));
                }
            }
            progressSink.accept(ContainerEvent.info("Parsing",
                    "Parsed " + filename + ": " + lineNumber + " lines"));
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

    private record PairingResult(List<ApiCallPair> pairs, List<OrphanRequest> orphans) {}

    private record JobPairingResult(List<JobExecution> executions, List<OrphanJob> orphans) {}

    private PairingResult pairApiCalls(List<LogLine> lines, Pattern apiCallPattern,
                                              int slowThresholdMs, List<Map.Entry<Pattern, String>> redactionPatterns,
                                              AtomicBoolean cancelled) {
        // Key: thread + "|" + endpoint + "|" + correlationId (or empty)
        Map<String, Deque<PendingRequest>> pendingByCorrelation = new HashMap<>();
        // Key: thread + "|" + endpoint (for calls without correlationId — FIFO queue)
        Map<String, Deque<PendingRequest>> pendingByThreadEndpoint = new HashMap<>();
        List<ApiCallPair> pairs = new ArrayList<>();
        int lineIndex = 0;

        for (LogLine line : lines) {
            if ((++lineIndex % 5000 == 0)) checkCancelled(cancelled);
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
                        matched = findClosestByTimestamp(queue, line.timestamp(), PendingRequest::timestamp);
                    }
                }

                if (matched != null && matched.timestamp != null && line.timestamp() != null) {
                    long durationMs = Duration.between(matched.timestamp, line.timestamp()).toMillis();
                    pairs.add(new ApiCallPair(
                            endpoint, matched.correlationId, thread,
                            matched.timestamp, line.timestamp(), durationMs,
                            redactPayload(matched.payload, redactionPatterns),
                            redactPayload(payload, redactionPatterns),
                            matched.lineNumber, line.lineNumber(),
                            matched.sourceFile, durationMs >= slowThresholdMs
                    ));
                }
            }
        }

        // Collect orphan requests (requests that never got a response)
        List<OrphanRequest> orphans = new ArrayList<>();
        for (Deque<PendingRequest> queue : pendingByCorrelation.values()) {
            for (PendingRequest p : queue) {
                orphans.add(new OrphanRequest(p.endpoint, p.thread, p.timestamp,
                        redactPayload(p.payload, redactionPatterns), p.lineNumber, p.sourceFile));
            }
        }
        for (Deque<PendingRequest> queue : pendingByThreadEndpoint.values()) {
            for (PendingRequest p : queue) {
                orphans.add(new OrphanRequest(p.endpoint, p.thread, p.timestamp,
                        redactPayload(p.payload, redactionPatterns), p.lineNumber, p.sourceFile));
            }
        }
        orphans.sort(Comparator.comparingInt(OrphanRequest::lineNumber));

        return new PairingResult(pairs, orphans);
    }

    /**
     * Find the pending entry whose timestamp is closest to the target timestamp.
     * Removes and returns the best match from the queue.
     * Falls back to FIFO (oldest) if timestamps are null.
     */
    private <T> T findClosestByTimestamp(Deque<T> queue, LocalDateTime targetTimestamp,
                                         Function<T, LocalDateTime> timestampAccessor) {
        if (queue.size() == 1 || targetTimestamp == null) {
            return queue.poll();
        }

        T best = null;
        long bestDistance = Long.MAX_VALUE;

        for (T pending : queue) {
            LocalDateTime ts = timestampAccessor.apply(pending);
            if (ts == null) continue;
            long distance = Math.abs(Duration.between(ts, targetTimestamp).toMillis());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pending;
            }
        }

        if (best != null) {
            queue.remove(best);
            return best;
        }
        return queue.poll();
    }

    private JobPairingResult pairJobExecutions(List<LogLine> lines, Pattern startPattern, Pattern endPattern,
                                                    AtomicBoolean cancelled) {
        // Key: thread + "|" + jobName
        Map<String, Deque<PendingJob>> pending = new HashMap<>();
        List<JobExecution> executions = new ArrayList<>();
        int lineIndex = 0;

        for (LogLine line : lines) {
            if ((++lineIndex % 5000 == 0)) checkCancelled(cancelled);
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
                    PendingJob job = findClosestByTimestamp(queue, line.timestamp(), PendingJob::timestamp);
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

        List<OrphanJob> orphans = new ArrayList<>();
        for (Deque<PendingJob> queue : pending.values()) {
            for (PendingJob p : queue) {
                orphans.add(new OrphanJob(p.jobName, p.trigger, p.thread,
                        p.timestamp, p.lineNumber, p.sourceFile));
            }
        }
        orphans.sort(Comparator.comparingInt(OrphanJob::lineNumber));

        return new JobPairingResult(executions, orphans);
    }

    private List<RepeatedFailure> detectRepeatedFailures(List<LogLine> lines, Pattern failurePattern,
                                                            AtomicBoolean cancelled) {
        // Key: entityId + "|" + reason
        Map<String, List<RepeatedFailure.FailureDetail>> grouped = new LinkedHashMap<>();
        Map<String, String> entityReasonMap = new LinkedHashMap<>();
        int lineIndex = 0;

        for (LogLine line : lines) {
            if ((++lineIndex % 5000 == 0)) checkCancelled(cancelled);
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
        String[] testInputs = {
            "a".repeat(1000),
            "ab".repeat(500),
            "a b c ".repeat(167),
            "abc123!@#".repeat(111),
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (String input : testInputs) {
                Future<?> future = executor.submit(() -> pattern.matcher(input).find());
                try {
                    future.get(REGEX_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw new IllegalArgumentException(
                            "Regex for '" + name + "' timed out on safety check. Please simplify the pattern.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException(
                            "Regex safety check for '" + name + "' was interrupted.");
                } catch (ExecutionException e) {
                    throw new IllegalArgumentException(
                            "Regex safety check for '" + name + "' failed: " + e.getCause().getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Map.Entry<Pattern, String>> compileRedactionPatterns(List<String> sensitiveFieldNames) {
        if (sensitiveFieldNames == null || sensitiveFieldNames.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<Pattern, String>> patterns = new ArrayList<>();
        for (String fieldName : sensitiveFieldNames) {
            String escaped = Pattern.quote(fieldName);
            Pattern p = Pattern.compile("\"(" + escaped + ")\"\\s*:\\s*\"[^\"]*\"");
            patterns.add(Map.entry(p, "\"$1\":\"***\""));
        }
        return patterns;
    }

    private String redactPayload(String payload, List<Map.Entry<Pattern, String>> redactionPatterns) {
        if (payload == null || redactionPatterns.isEmpty()) {
            return payload;
        }
        String result = payload;
        for (Map.Entry<Pattern, String> entry : redactionPatterns) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return result;
    }

    private record PendingRequest(String endpoint, String correlationId, String thread,
                                  LocalDateTime timestamp, String payload, int lineNumber, String sourceFile) {}

    private record PendingJob(String jobName, String trigger, String thread,
                              LocalDateTime timestamp, int lineNumber, String sourceFile) {}
}
