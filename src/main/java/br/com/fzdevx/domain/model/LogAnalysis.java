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

    public LogAnalysis(List<SourceFile> sourceFiles, int totalLineCount,
                       LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd,
                       List<String> threads, List<String> endpoints,
                       List<ApiCallPair> apiCalls, List<EndpointStats> endpointStats,
                       Map<String, Integer> levelCounts, List<LogLine> errors,
                       List<JobExecution> jobExecutions, List<RepeatedFailure> repeatedFailures,
                       List<LogLine> allLines) {
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
}
