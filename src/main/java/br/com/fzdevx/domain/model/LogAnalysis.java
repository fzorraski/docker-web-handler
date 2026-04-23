package br.com.fzdevx.domain.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LogAnalysis {

    private final String id;
    private final List<SourceFile> sourceFiles;
    private final int totalLineCount;
    private final Instant uploadedAt;
    private final LocalDateTime timeRangeStart;
    private final LocalDateTime timeRangeEnd;
    private final List<String> threads;
    private final List<String> endpoints;
    private final List<ApiCallPair> apiCalls;
    private final List<EndpointStats> endpointStats;
    private final Map<String, Integer> levelCounts;
    private final List<LogLine> errors;
    private final List<JobExecution> jobExecutions;
    private final List<RepeatedFailure> repeatedFailures;
    private final List<LogLine> allLines;
    private final List<OrphanRequest> orphanRequests;
    private final List<OrphanJob> orphanJobs;
    private List<CustomFieldResult> customFieldResults;
    private List<CriticalIssueSummary> criticalIssues;
    private List<CriticalIssueSummary> cachedBursts;
    private List<NpeLocationSummary> npeAnalysis;
    private List<ExceptionLocationSummary> exceptionAnalysis;
    private String label;

    public LogAnalysis(List<SourceFile> sourceFiles, int totalLineCount,
                       LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd,
                       List<String> threads, List<String> endpoints,
                       List<ApiCallPair> apiCalls, List<EndpointStats> endpointStats,
                       Map<String, Integer> levelCounts, List<LogLine> errors,
                       List<JobExecution> jobExecutions, List<RepeatedFailure> repeatedFailures,
                       List<LogLine> allLines, List<OrphanRequest> orphanRequests,
                       List<OrphanJob> orphanJobs) {
        this.id = UUID.randomUUID().toString();
        this.sourceFiles = sourceFiles;
        this.totalLineCount = totalLineCount;
        this.uploadedAt = Instant.now();
        this.timeRangeStart = timeRangeStart;
        this.timeRangeEnd = timeRangeEnd;
        this.threads = threads;
        this.endpoints = endpoints;
        this.apiCalls = apiCalls;
        this.endpointStats = endpointStats;
        this.levelCounts = levelCounts;
        this.errors = errors;
        this.jobExecutions = jobExecutions;
        this.repeatedFailures = repeatedFailures;
        this.allLines = allLines;
        this.orphanRequests = orphanRequests != null ? orphanRequests : List.of();
        this.orphanJobs = orphanJobs != null ? orphanJobs : List.of();
        this.customFieldResults = List.of();
        this.criticalIssues = List.of();
        this.npeAnalysis = List.of();
        this.exceptionAnalysis = List.of();
    }

    /** Backwards-compatible constructor without orphans. */
    public LogAnalysis(List<SourceFile> sourceFiles, int totalLineCount,
                       LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd,
                       List<String> threads, List<String> endpoints,
                       List<ApiCallPair> apiCalls, List<EndpointStats> endpointStats,
                       Map<String, Integer> levelCounts, List<LogLine> errors,
                       List<JobExecution> jobExecutions, List<RepeatedFailure> repeatedFailures,
                       List<LogLine> allLines) {
        this(sourceFiles, totalLineCount, timeRangeStart, timeRangeEnd, threads, endpoints,
                apiCalls, endpointStats, levelCounts, errors, jobExecutions, repeatedFailures,
                allLines, List.of(), List.of());
    }

    public record SourceFile(String filename, long size) {}

    public String getId() { return id; }
    public List<SourceFile> getSourceFiles() { return sourceFiles; }
    public int getTotalLineCount() { return totalLineCount; }
    public Instant getUploadedAt() { return uploadedAt; }
    public LocalDateTime getTimeRangeStart() { return timeRangeStart; }
    public LocalDateTime getTimeRangeEnd() { return timeRangeEnd; }
    public List<String> getThreads() { return threads; }
    public List<String> getEndpoints() { return endpoints; }
    public List<ApiCallPair> getApiCalls() { return apiCalls; }
    public List<EndpointStats> getEndpointStats() { return endpointStats; }
    public Map<String, Integer> getLevelCounts() { return levelCounts; }
    public List<LogLine> getErrors() { return errors; }
    public List<JobExecution> getJobExecutions() { return jobExecutions; }
    public List<RepeatedFailure> getRepeatedFailures() { return repeatedFailures; }
    public List<LogLine> getAllLines() { return allLines; }
    public List<OrphanRequest> getOrphanRequests() { return orphanRequests; }
    public List<OrphanJob> getOrphanJobs() { return orphanJobs; }
    public List<CustomFieldResult> getCustomFieldResults() { return customFieldResults; }

    public void setCustomFieldResults(List<CustomFieldResult> customFieldResults) {
        this.customFieldResults = customFieldResults != null ? customFieldResults : List.of();
    }

    public List<CriticalIssueSummary> getCriticalIssues() { return criticalIssues; }

    public void setCriticalIssues(List<CriticalIssueSummary> criticalIssues) {
        this.criticalIssues = criticalIssues != null ? criticalIssues : List.of();
    }

    public List<CriticalIssueSummary> getCachedBursts() { return cachedBursts; }
    public void setCachedBursts(List<CriticalIssueSummary> cachedBursts) { this.cachedBursts = cachedBursts; }

    public List<NpeLocationSummary> getNpeAnalysis() { return npeAnalysis; }

    public void setNpeAnalysis(List<NpeLocationSummary> npeAnalysis) {
        this.npeAnalysis = npeAnalysis != null ? npeAnalysis : List.of();
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public List<ExceptionLocationSummary> getExceptionAnalysis() { return exceptionAnalysis; }

    public void setExceptionAnalysis(List<ExceptionLocationSummary> exceptionAnalysis) {
        this.exceptionAnalysis = exceptionAnalysis != null ? exceptionAnalysis : List.of();
    }
}
