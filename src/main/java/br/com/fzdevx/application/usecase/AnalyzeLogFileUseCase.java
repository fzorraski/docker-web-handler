package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.port.CustomFieldExtractorPort;
import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.infrastructure.log.CriticalIssueDetector;
import br.com.fzdevx.infrastructure.log.ExceptionAnalyzer;
import br.com.fzdevx.infrastructure.log.NpeAnalyzer;
import br.com.fzdevx.domain.shared.EndpointStatsCalculator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

@ApplicationScoped
public class AnalyzeLogFileUseCase {

    private static final Logger LOG = Logger.getLogger(AnalyzeLogFileUseCase.class.getName());

    public record AnalysisResult(LogAnalysis analysis, String evictedId) {}

    private final ConcurrentHashMap<String, AnalysisEntry> analyses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupScheduler;

    @Inject
    LogAnalysisPort logAnalysisPort;

    @Inject
    CustomFieldExtractorPort customFieldExtractorPort;

    @Inject
    CriticalIssueDetector criticalIssueDetector;

    @Inject
    NpeAnalyzer npeAnalyzer;

    @Inject
    ExceptionAnalyzer exceptionAnalyzer;

    @Inject
    br.com.fzdevx.infrastructure.persistence.ResourceCounterService resourceCounterService;

    @Inject
    @ConfigProperty(name = "log.analyzer.max-files", defaultValue = "5")
    int maxFiles;

    @Inject
    @ConfigProperty(name = "log.analyzer.file-ttl-minutes", defaultValue = "120")
    int ttlMinutes;

    @PostConstruct
    void init() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-analyzer-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
        analyses.clear();
    }

    public AnalysisResult analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs) {
        return analyze(files, filenames, preset, slowThresholdMs, AnalysisOptions.all());
    }

    public AnalysisResult analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                               AnalysisOptions options) {
        String evictedId = null;
        if (analyses.size() >= maxFiles) {
            evictedId = evictOldest();
        }

        LogAnalysis analysis = logAnalysisPort.analyze(files, filenames, preset, slowThresholdMs, options);

        if (options.customFields() && preset.hasCustomFields()) {
            analysis.setCustomFieldResults(
                    customFieldExtractorPort.extract(analysis.getAllLines(), preset.customFields())
            );
        }

        if (options.criticalIssues()) {
            analysis.setCriticalIssues(criticalIssueDetector.detect(analysis.getAllLines(), preset.criticalIssueExclusions()));
        }
        if (options.npeAnalysis()) {
            analysis.setNpeAnalysis(npeAnalyzer.analyze(analysis.getAllLines()));
        }
        if (options.exceptionAnalysis()) {
            analysis.setExceptionAnalysis(exceptionAnalyzer.analyze(analysis.getAllLines()));
        }

        analyses.put(analysis.getId(), new AnalysisEntry(analysis, Instant.now()));
        resourceCounterService.increment(br.com.fzdevx.infrastructure.persistence.ResourceCounterService.LOGS_ANALYZED);
        LOG.info(String.format("Log analysis '%s' created: %d lines, %d API calls, %d endpoints from %d file(s)",
                analysis.getId(), analysis.getTotalLineCount(),
                analysis.getApiCalls().size(), analysis.getEndpoints().size(),
                analysis.getSourceFiles().size()));
        return new AnalysisResult(analysis, evictedId);
    }

    public void analyzeWithProgress(List<Path> files, List<String> filenames, LogPreset preset,
                                     int slowThresholdMs, AnalysisOptions options,
                                     Consumer<ContainerEvent> eventSink, String ticket,
                                     Consumer<String> onEvicted) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeRuns.put(ticket, cancelled);
        try {
            if (analyses.size() >= maxFiles) {
                String evictedId = evictOldest();
                if (evictedId != null) onEvicted.accept(evictedId);
            }

            eventSink.accept(ContainerEvent.info("Parsing", "Starting analysis..."));
            LogAnalysis analysis = logAnalysisPort.analyze(files, filenames, preset, slowThresholdMs, options, eventSink, cancelled);

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Cancelled", "Analysis cancelled."));
                return;
            }

            if (options.customFields() && preset.hasCustomFields()) {
                eventSink.accept(ContainerEvent.info("Custom Fields", "Extracting custom fields..."));
                checkCancelled(cancelled);
                analysis.setCustomFieldResults(
                        customFieldExtractorPort.extract(analysis.getAllLines(), preset.customFields())
                );
                eventSink.accept(ContainerEvent.info("Custom Fields",
                        "Custom fields: " + analysis.getCustomFieldResults().size() + " field(s) processed"));
            }

            if (options.criticalIssues()) {
                eventSink.accept(ContainerEvent.info("Critical Issues", "Detecting critical issues..."));
                checkCancelled(cancelled);
                analysis.setCriticalIssues(criticalIssueDetector.detect(analysis.getAllLines(), preset.criticalIssueExclusions()));
            }

            if (options.npeAnalysis()) {
                eventSink.accept(ContainerEvent.info("NPE Analysis", "Analyzing NullPointerExceptions..."));
                checkCancelled(cancelled);
                analysis.setNpeAnalysis(npeAnalyzer.analyze(analysis.getAllLines()));
            }

            if (options.exceptionAnalysis()) {
                eventSink.accept(ContainerEvent.info("Exception Analysis", "Analyzing exceptions..."));
                checkCancelled(cancelled);
                analysis.setExceptionAnalysis(exceptionAnalyzer.analyze(analysis.getAllLines()));
            }

            analyses.put(analysis.getId(), new AnalysisEntry(analysis, Instant.now()));
            resourceCounterService.increment(br.com.fzdevx.infrastructure.persistence.ResourceCounterService.LOGS_ANALYZED);

            eventSink.accept(ContainerEvent.success("Complete",
                    "Analysis complete: " + analysis.getTotalLineCount() + " lines, "
                            + analysis.getApiCalls().size() + " API calls",
                    analysis.getId()));
        } catch (CancellationException e) {
            eventSink.accept(ContainerEvent.error("Cancelled", "Analysis cancelled."));
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Error", "Analysis failed: " + e.getMessage()));
        } finally {
            activeRuns.remove(ticket);
        }
    }

    public boolean cancel(String ticket) {
        AtomicBoolean cancelled = activeRuns.get(ticket);
        if (cancelled == null) return false;
        cancelled.set(true);
        return true;
    }

    private void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) throw new CancellationException("Analysis cancelled");
    }

    public LogAnalysis compose(List<String> analysisIds, LogPreset preset, int slowThresholdMs) {
        return compose(analysisIds, preset, slowThresholdMs, AnalysisOptions.all());
    }

    public LogAnalysis compose(List<String> analysisIds, LogPreset preset, int slowThresholdMs,
                               AnalysisOptions options) {
        List<LogAnalysis> toCompose = new ArrayList<>();
        for (String id : analysisIds) {
            AnalysisEntry entry = analyses.get(id);
            if (entry != null) {
                toCompose.add(entry.analysis);
            }
        }
        if (toCompose.isEmpty()) {
            return null;
        }

        return mergeAnalyses(toCompose, preset, slowThresholdMs, options);
    }

    private LogAnalysis mergeAnalyses(List<LogAnalysis> sources, LogPreset preset, int slowThresholdMs,
                                      AnalysisOptions options) {
        var mergedLines = new ArrayList<LogLine>();
        var mergedSourceFiles = new ArrayList<LogAnalysis.SourceFile>();

        for (LogAnalysis src : sources) {
            mergedLines.addAll(src.getAllLines());
            mergedSourceFiles.addAll(src.getSourceFiles());
        }

        mergedLines.sort(Comparator.comparing(
                LogLine::timestamp,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        return buildMergedAnalysis(mergedSourceFiles, mergedLines, sources, preset, slowThresholdMs, options);
    }

    private LogAnalysis buildMergedAnalysis(List<LogAnalysis.SourceFile> sourceFiles,
                                            List<LogLine> allLines,
                                            List<LogAnalysis> sources,
                                            LogPreset preset, int slowThresholdMs,
                                            AnalysisOptions options) {
        var apiCalls = new ArrayList<ApiCallPair>();
        var jobExecs = new ArrayList<JobExecution>();
        var errors = new ArrayList<LogLine>();
        var levelCounts = new LinkedHashMap<String, Integer>();
        var threads = new LinkedHashSet<String>();
        var endpoints = new LinkedHashSet<String>();

        // Collect all failure details grouped by entityId + reason for deduplication
        var failureGroups = new LinkedHashMap<String, List<RepeatedFailure>>();

        for (LogAnalysis src : sources) {
            apiCalls.addAll(src.getApiCalls());
            jobExecs.addAll(src.getJobExecutions());
            errors.addAll(src.getErrors());
            src.getLevelCounts().forEach((k, v) -> levelCounts.merge(k, v, Integer::sum));
            threads.addAll(src.getThreads());
            endpoints.addAll(src.getEndpoints());
            for (RepeatedFailure f : src.getRepeatedFailures()) {
                String key = f.entityId() + "|" + (f.reason() != null ? f.reason() : "");
                failureGroups.computeIfAbsent(key, _ -> new ArrayList<>()).add(f);
            }
        }

        // Deduplicate failures: merge details, sum occurrences, take min firstSeen and max lastSeen
        var failures = failureGroups.entrySet().stream().map(e -> {
            List<RepeatedFailure> group = e.getValue();
            RepeatedFailure first = group.getFirst();

            var mergedDetails = group.stream()
                    .flatMap(f -> f.details().stream())
                    .sorted(Comparator.comparing(RepeatedFailure.FailureDetail::timestamp,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            int totalOccurrences = group.stream().mapToInt(RepeatedFailure::occurrences).sum();

            LocalDateTime minFirstSeen = group.stream()
                    .map(RepeatedFailure::firstSeen)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);

            LocalDateTime maxLastSeen = group.stream()
                    .map(RepeatedFailure::lastSeen)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            return new RepeatedFailure(
                    first.entityId(), first.reason(), totalOccurrences,
                    minFirstSeen, maxLastSeen, mergedDetails
            );
        }).sorted(Comparator.comparingInt(RepeatedFailure::occurrences).reversed()).toList();

        apiCalls.sort(Comparator.comparing(
                ApiCallPair::requestTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        var start = allLines.stream()
                .map(LogLine::timestamp)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        var end = allLines.stream()
                .map(LogLine::timestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);

        var endpointStats = EndpointStatsCalculator.compute(apiCalls, slowThresholdMs);

        // Merge custom field results: combine by fieldName, sum matchCount, concatenate matches
        var customFieldGroups = new LinkedHashMap<String, List<CustomFieldResult>>();
        for (LogAnalysis src : sources) {
            for (CustomFieldResult cfr : src.getCustomFieldResults()) {
                customFieldGroups.computeIfAbsent(cfr.fieldName(), _ -> new ArrayList<>()).add(cfr);
            }
        }
        var mergedCustomFields = customFieldGroups.entrySet().stream().map(e -> {
            List<CustomFieldResult> group = e.getValue();
            int totalCount = group.stream().mapToInt(CustomFieldResult::matchCount).sum();
            boolean countOnly = group.getFirst().countOnly();
            var mergedMatches = countOnly ? List.<CustomFieldMatch>of()
                    : group.stream().flatMap(r -> r.matches().stream()).toList();
            return new CustomFieldResult(e.getKey(), totalCount, countOnly, mergedMatches);
        }).toList();

        LogAnalysis merged = new LogAnalysis(
                sourceFiles, allLines.size(), start, end,
                new ArrayList<>(threads), new ArrayList<>(endpoints),
                apiCalls, endpointStats, levelCounts, errors,
                jobExecs, failures, allLines
        );
        merged.setCustomFieldResults(mergedCustomFields);
        if (options.criticalIssues()) {
            merged.setCriticalIssues(criticalIssueDetector.detect(allLines, preset.criticalIssueExclusions()));
        }
        if (options.npeAnalysis()) {
            merged.setNpeAnalysis(npeAnalyzer.analyze(allLines));
        }
        if (options.exceptionAnalysis()) {
            merged.setExceptionAnalysis(exceptionAnalyzer.analyze(allLines));
        }
        analyses.put(merged.getId(), new AnalysisEntry(merged, Instant.now()));
        return merged;
    }

    public LogAnalysis get(String id) {
        AnalysisEntry entry = analyses.get(id);
        return entry != null ? entry.analysis : null;
    }

    public List<LogAnalysis> listAll() {
        return analyses.values().stream()
                .map(e -> e.analysis)
                .sorted((a, b) -> b.getUploadedAt().compareTo(a.getUploadedAt()))
                .toList();
    }

    public boolean delete(String id) {
        return analyses.remove(id) != null;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlMinutes * 60L);
        analyses.entrySet().removeIf(e -> e.getValue().createdAt.isBefore(cutoff));
    }

    private String evictOldest() {
        return analyses.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.comparing(e -> e.createdAt)))
                .map(oldest -> {
                    analyses.remove(oldest.getKey());
                    LOG.info(String.format("Evicted oldest log analysis '%s' to make room", oldest.getKey()));
                    return oldest.getKey();
                }).orElse(null);
    }

    public int getMaxFiles() { return maxFiles; }

    public int getAnalysisCount() { return analyses.size(); }

    private record AnalysisEntry(LogAnalysis analysis, Instant createdAt) {}
}
