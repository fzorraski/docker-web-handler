package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.CustomFieldExtractorPort;
import br.com.fzdevx.application.port.LogAnalysisPort;
import br.com.fzdevx.domain.model.*;
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
import java.util.logging.Logger;

@ApplicationScoped
public class AnalyzeLogFileUseCase {

    private static final Logger LOG = Logger.getLogger(AnalyzeLogFileUseCase.class.getName());

    private final ConcurrentHashMap<String, AnalysisEntry> analyses = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupScheduler;

    @Inject
    LogAnalysisPort logAnalysisPort;

    @Inject
    CustomFieldExtractorPort customFieldExtractorPort;

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

    public LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs) {
        if (analyses.size() >= maxFiles) {
            evictOldest();
        }

        LogAnalysis analysis = logAnalysisPort.analyze(files, filenames, preset, slowThresholdMs);

        if (preset.hasCustomFields()) {
            analysis.setCustomFieldResults(
                    customFieldExtractorPort.extract(analysis.getAllLines(), preset.customFields())
            );
        }

        analyses.put(analysis.getId(), new AnalysisEntry(analysis, Instant.now()));
        LOG.info(String.format("Log analysis '%s' created: %d lines, %d API calls, %d endpoints from %d file(s)",
                analysis.getId(), analysis.getTotalLineCount(),
                analysis.getApiCalls().size(), analysis.getEndpoints().size(),
                analysis.getSourceFiles().size()));
        return analysis;
    }

    public LogAnalysis compose(List<String> analysisIds, LogPreset preset, int slowThresholdMs) {
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

        return mergeAnalyses(toCompose, preset, slowThresholdMs);
    }

    private LogAnalysis mergeAnalyses(List<LogAnalysis> sources, LogPreset preset, int slowThresholdMs) {
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

        return buildMergedAnalysis(mergedSourceFiles, mergedLines, sources, preset, slowThresholdMs);
    }

    private LogAnalysis buildMergedAnalysis(List<LogAnalysis.SourceFile> sourceFiles,
                                            List<LogLine> allLines,
                                            List<LogAnalysis> sources,
                                            LogPreset preset, int slowThresholdMs) {
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

    private void evictOldest() {
        analyses.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.comparing(e -> e.createdAt)))
                .ifPresent(oldest -> {
                    analyses.remove(oldest.getKey());
                    LOG.info(String.format("Evicted oldest log analysis '%s' to make room", oldest.getKey()));
                });
    }

    private record AnalysisEntry(LogAnalysis analysis, Instant createdAt) {}
}
