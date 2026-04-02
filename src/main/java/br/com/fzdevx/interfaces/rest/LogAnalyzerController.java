package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.usecase.AnalyzeContainerLogsUseCase;
import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.model.anomaly.*;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.domain.shared.PerformanceInsightsCalculator;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import br.com.fzdevx.infrastructure.log.CriticalIssueDetector;
import br.com.fzdevx.infrastructure.log.anomaly.AnomalyDetectorService;
import br.com.fzdevx.infrastructure.log.anomaly.CorrelationDetector;
import br.com.fzdevx.infrastructure.log.anomaly.SignalExtractor;
import br.com.fzdevx.infrastructure.log.anomaly.TimeBucketAggregator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Path("/logs/analyzer")
public class LogAnalyzerController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    AnalyzeLogFileUseCase analyzeLogFileUseCase;

    @Inject
    AnalyzeContainerLogsUseCase analyzeContainerLogsUseCase;

    @Inject
    LogPresetProvider logPresetProvider;

    @Inject
    CriticalIssueDetector criticalIssueDetector;

    @Inject
    SignalExtractor signalExtractor;

    @Inject
    AnomalyDetectorService anomalyDetectorService;

    @Inject
    @ConfigProperty(name = "log.analyzer.enabled", defaultValue = "false")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "log.analyzer.max-file-size-mb", defaultValue = "500")
    int maxFileSizeMb;

    @Inject
    @ConfigProperty(name = "log.analyzer.slow-threshold-ms", defaultValue = "1000")
    int defaultSlowThresholdMs;

    @Inject
    @ConfigProperty(name = "log.analyzer.container-tail", defaultValue = "10000")
    int defaultContainerTail;

    @Inject
    @ConfigProperty(name = "log.analyzer.default-preset", defaultValue = "WILDFLY")
    String defaultPresetName;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStatus() {
        return Response.ok(Map.of(
                "enabled", enabled,
                "presets", logPresetProvider.allPresets().stream().map(this::presetToMap).toList(),
                "defaultPreset", defaultPresetName,
                "containerTail", defaultContainerTail
        )).build();
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadAndAnalyze(MultipartFormDataInput input) {
        if (!enabled) return featureDisabled();

        List<java.nio.file.Path> tempFiles = new ArrayList<>();
        List<java.nio.file.Path> tempDirs = new ArrayList<>();
        try {
            Map<String, List<InputPart>> form = input.getFormDataMap();

            String presetName = extractString(form, "preset");
            LogPreset basePreset = presetName != null ? logPresetProvider.byName(presetName) : logPresetProvider.byName(defaultPresetName);

            String customLogLineRegex = extractString(form, "logLineRegex");
            String customApiCallRegex = extractString(form, "apiCallRegex");
            String customTimestampFormat = extractString(form, "timestampFormat");
            String customJobStartRegex = extractString(form, "jobStartRegex");
            String customJobEndRegex = extractString(form, "jobEndRegex");
            String customFailureRegex = extractString(form, "failureRegex");
            String customSensitiveFields = extractString(form, "sensitiveFieldNames");
            String customFieldsJson = extractString(form, "customFields");

            List<LogPreset.CustomField> uploadCustomFields = parseCustomFieldsJson(customFieldsJson);
            List<LogPreset.CustomField> mergedCustomFields = uploadCustomFields.isEmpty()
                    ? basePreset.customFields()
                    : uploadCustomFields;

            LogPreset preset = new LogPreset(
                    basePreset.name(),
                    nonBlankOrDefault(customLogLineRegex, basePreset.logLineRegex()),
                    nonBlankOrDefault(customTimestampFormat, basePreset.timestampFormat()),
                    nonBlankOrDefault(customApiCallRegex, basePreset.apiCallRegex()),
                    nonBlankOrDefault(customJobStartRegex, basePreset.jobStartRegex()),
                    nonBlankOrDefault(customJobEndRegex, basePreset.jobEndRegex()),
                    nonBlankOrDefault(customFailureRegex, basePreset.failureRegex()),
                    customSensitiveFields != null && !customSensitiveFields.isBlank()
                            ? Arrays.asList(customSensitiveFields.split(","))
                            : basePreset.sensitiveFieldNames(),
                    mergedCustomFields
            );

            if (preset.logLineRegex() == null || preset.logLineRegex().isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Log line regex is required."))
                        .build();
            }

            String slowThresholdStr = extractString(form, "slowThresholdMs");
            int slowThresholdMs = slowThresholdStr != null
                    ? Integer.parseInt(slowThresholdStr)
                    : defaultSlowThresholdMs;

            List<InputPart> fileParts = form.get("files");
            if (fileParts == null || fileParts.isEmpty()) {
                fileParts = form.get("file");
            }
            if (fileParts == null || fileParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "No file(s) provided."))
                        .build();
            }

            List<String> filenames = new ArrayList<>();
            long maxBytes = (long) maxFileSizeMb * 1024 * 1024;

            for (InputPart filePart : fileParts) {
                String filename = extractFilename(filePart);
                if (filename == null || filename.isBlank()) {
                    filename = "unknown-" + (filenames.size() + 1) + ".log";
                }

                Optional<String> filenameError = InputValidator.validateUploadFilename(filename);
                if (filenameError.isPresent()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", filenameError.get()))
                            .build();
                }

                java.nio.file.Path tempDir = Files.createTempDirectory("log-analyzer-");
                tempDirs.add(tempDir);
                java.nio.file.Path tempFile = tempDir.resolve(filename);
                tempFiles.add(tempFile);
                filenames.add(filename);

                try (InputStream is = filePart.getBody(InputStream.class, null);
                     var out = Files.newOutputStream(tempFile)) {
                    long size = 0;
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        size += read;
                        if (size > maxBytes) {
                            return Response.status(Response.Status.BAD_REQUEST)
                                    .entity(Map.of("error", "File '" + filename + "' exceeds the maximum size of " + maxFileSizeMb + " MB."))
                                    .build();
                        }
                        out.write(buf, 0, read);
                    }
                }
            }

            AnalysisOptions options = parseAnalysisOptions(extractString(form, "options"));

            LogAnalysis analysis = analyzeLogFileUseCase.analyze(tempFiles, filenames, preset, slowThresholdMs, options);
            return Response.ok(analysisSummaryMap(analysis)).build();

        } catch (NumberFormatException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid numeric parameter."))
                    .build();
        } catch (PatternSyntaxException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid regex pattern: " + e.getDescription()))
                    .build();
        } catch (Exception e) {
            Log.errorf("Log analysis failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Log analysis failed. Please try again."))
                    .build();
        } finally {
            for (java.nio.file.Path f : tempFiles) {
                try { Files.deleteIfExists(f); } catch (Exception ignored) {}
            }
            for (java.nio.file.Path d : tempDirs) {
                try { Files.deleteIfExists(d); } catch (Exception ignored) {}
            }
        }
    }

    @POST
    @Path("/from-container/{containerId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyzeContainerLogs(@PathParam("containerId") String containerId,
                                         @QueryParam("containerName") String containerName,
                                         @QueryParam("preset") String presetName,
                                         @QueryParam("slowThresholdMs") Integer slowThresholdMs,
                                         @QueryParam("lines") Integer lines,
                                         @QueryParam("direction") @DefaultValue("tail") String direction) {
        if (!enabled) return featureDisabled();

        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", idError.get()))
                    .build();
        }

        int requestedLines = Math.clamp(lines != null ? lines : defaultContainerTail, 100, 100_000);
        LogPreset preset = presetName != null ? logPresetProvider.byName(presetName) : logPresetProvider.byName(defaultPresetName);
        int threshold = slowThresholdMs != null ? slowThresholdMs : defaultSlowThresholdMs;

        try {
            LogAnalysis analysis = analyzeContainerLogsUseCase.execute(
                    containerId, containerName, requestedLines, direction, preset, threshold
            );
            return Response.ok(analysisSummaryMap(analysis)).build();
        } catch (AnalyzeContainerLogsUseCase.ContainerLogException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            Log.errorf("Container log analysis failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to analyze container logs."))
                    .build();
        }
    }

    @POST
    @Path("/compose")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response compose(Map<String, Object> body) {
        if (!enabled) return featureDisabled();

        Object idsObj = body.get("ids");
        if (!(idsObj instanceof List<?> rawList)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "'ids' must be an array."))
                    .build();
        }
        List<String> ids = rawList.stream().map(Object::toString).toList();
        if (ids.size() < 2) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "At least 2 analysis IDs are required."))
                    .build();
        }

        String presetName = (String) body.get("preset");
        LogPreset preset = presetName != null ? logPresetProvider.byName(presetName) : logPresetProvider.byName(defaultPresetName);

        Object slowObj = body.get("slowThresholdMs");
        int slowThresholdMs = slowObj instanceof Number n ? n.intValue() : defaultSlowThresholdMs;

        AnalysisOptions options = parseAnalysisOptionsFromMap(body.get("options"));

        LogAnalysis composed = analyzeLogFileUseCase.compose(ids, preset, slowThresholdMs, options);
        if (composed == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No valid analyses found for the given IDs."))
                    .build();
        }
        return Response.ok(analysisSummaryMap(composed)).build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysis(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return Response.ok(analysisSummaryMap(analysis)).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAnalysis(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        boolean deleted = analyzeLogFileUseCase.delete(id);
        if (!deleted) return analysisNotFound();
        return Response.ok(Map.of("deleted", true)).build();
    }

    @GET
    @Path("/{id}/api-calls")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApiCalls(@PathParam("id") String id,
                                @QueryParam("endpoint") String endpoint,
                                @QueryParam("thread") String thread,
                                @QueryParam("minDuration") Long minDuration,
                                @QueryParam("search") String search,
                                @QueryParam("sort") @DefaultValue("time") String sort,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        String searchTerm = search != null && !search.isBlank() ? search.trim() : null;
        var filtered = analysis.getApiCalls().stream()
                .filter(c -> endpoint == null || endpoint.isBlank() || c.endpoint().equals(endpoint))
                .filter(c -> thread == null || thread.isBlank() || c.thread().equals(thread))
                .filter(c -> minDuration == null || c.durationMs() >= minDuration)
                .filter(c -> searchTerm == null || containsIgnoreCase(c, searchTerm));

        var sorted = switch (sort) {
            case "duration" -> filtered.sorted(Comparator.comparingLong(ApiCallPair::durationMs).reversed());
            case "endpoint" -> filtered.sorted(Comparator.comparing(ApiCallPair::endpoint));
            default -> filtered.sorted(Comparator.comparing(ApiCallPair::requestTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
        };

        return paginatedResponse(sorted.toList(), page, size);
    }

    @GET
    @Path("/{id}/api-stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApiStats(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return Response.ok(analysis.getEndpointStats()).build();
    }

    @GET
    @Path("/{id}/performance-insights")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPerformanceInsights(@PathParam("id") String id,
                                           @QueryParam("endpoint") String endpoint) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var calls = analysis.getApiCalls();
        if (endpoint != null && !endpoint.isBlank()) {
            calls = calls.stream().filter(c -> endpoint.equals(c.endpoint())).toList();
        }
        if (calls.isEmpty()) {
            return Response.ok(new PerformanceInsights(List.of(), List.of(), "1m", 0)).build();
        }
        PerformanceInsights insights = PerformanceInsightsCalculator.compute(
                calls,
                analysis.getTimeRangeStart(),
                analysis.getTimeRangeEnd()
        );
        return Response.ok(insights).build();
    }

    @GET
    @Path("/{id}/bucket-endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getBucketEndpoints(@PathParam("id") String id,
                                       @QueryParam("timestamp") String timestamp,
                                       @QueryParam("endpoint") String endpoint,
                                       @QueryParam("limit") @DefaultValue("7") int limit) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        if (timestamp == null || timestamp.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "timestamp query parameter is required"))
                    .build();
        }
        limit = Math.clamp(limit, 1, 100);
        var calls = analysis.getApiCalls();
        if (endpoint != null && !endpoint.isBlank()) {
            calls = calls.stream().filter(c -> endpoint.equals(c.endpoint())).toList();
        }
        try {
            var bucketEndpoints = PerformanceInsightsCalculator.computeBucketEndpoints(
                    calls, analysis.getTimeRangeStart(), analysis.getTimeRangeEnd(), timestamp, limit);
            return Response.ok(bucketEndpoints).build();
        } catch (DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid timestamp format. Expected ISO-8601 date-time."))
                    .build();
        }
    }

    @GET
    @Path("/{id}/lines")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLines(@PathParam("id") String id,
                             @QueryParam("thread") String thread,
                             @QueryParam("level") String level,
                             @QueryParam("search") String search,
                             @QueryParam("page") @DefaultValue("0") int page,
                             @QueryParam("size") @DefaultValue("100") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        String searchLower = search != null ? search.toLowerCase() : null;
        var filtered = analysis.getAllLines().stream()
                .filter(l -> thread == null || thread.isBlank() || thread.equals(l.thread()))
                .filter(l -> level == null || level.isBlank() || matchesLevelGroup(level, l.level()))
                .filter(l -> searchLower == null || (l.message() != null && l.message().toLowerCase().contains(searchLower)));

        return paginatedResponse(filtered.toList(), page, size);
    }

    @GET
    @Path("/{id}/lines/range")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLineRange(@PathParam("id") String id,
                                  @QueryParam("from") @DefaultValue("0") int from,
                                  @QueryParam("to") @DefaultValue("0") int to,
                                  @QueryParam("level") String level,
                                  @QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("size") @DefaultValue("500") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        if (from <= 0 || to <= 0 || from > to) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid range. 'from' and 'to' must be positive and from <= to."))
                    .build();
        }
        var rangeLines = analysis.getAllLines().stream()
                .filter(l -> l.lineNumber() >= from && l.lineNumber() <= to)
                .filter(l -> level == null || level.isBlank() || matchesLevelGroup(level, l.level()))
                .toList();
        return paginatedResponse(rangeLines, page, Math.clamp(size, 1, 1000));
    }

    @GET
    @Path("/{id}/threads")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getThreads(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var threadCounts = analysis.getAllLines().stream()
                .filter(l -> l.thread() != null)
                .collect(Collectors.groupingBy(LogLine::thread, Collectors.counting()));

        var threads = threadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Map.of("thread", (Object) e.getKey(), "lineCount", (Object) e.getValue()))
                .toList();

        return Response.ok(threads).build();
    }

    @GET
    @Path("/{id}/endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEndpoints(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return Response.ok(analysis.getEndpoints()).build();
    }

    @GET
    @Path("/{id}/jobs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJobs(@PathParam("id") String id,
                            @QueryParam("page") @DefaultValue("0") int page,
                            @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return paginatedResponse(analysis.getJobExecutions(), page, size);
    }

    @GET
    @Path("/{id}/failures")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFailures(@PathParam("id") String id,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return paginatedResponse(analysis.getRepeatedFailures(), page, size);
    }

    @GET
    @Path("/{id}/critical-issues")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCriticalIssues(@PathParam("id") String id,
                                      @QueryParam("category") String category) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var summaries = analysis.getCriticalIssues();
        if (category != null && !category.isBlank()) {
            summaries = summaries.stream()
                    .filter(s -> s.category().equalsIgnoreCase(category))
                    .toList();
        }
        return Response.ok(summaries).build();
    }

    @GET
    @Path("/{id}/critical-issues/bursts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCriticalBursts(@PathParam("id") String id,
                                      @QueryParam("threshold") Integer threshold,
                                      @QueryParam("window") Integer windowMinutes) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        List<CriticalIssueSummary> withBursts;
        if (threshold != null || windowMinutes != null) {
            // Custom params — bypass cache, compute fresh
            withBursts = criticalIssueDetector.computeBursts(analysis.getCriticalIssues(),
                    threshold != null ? Math.clamp(threshold, 2, 1000) : 10,
                    windowMinutes != null ? Math.clamp(windowMinutes, 1, 60) : 5);
        } else {
            withBursts = getOrComputeBursts(analysis);
        }
        // Return only category-level summaries (no individual bursts — those are fetched on demand)
        var result = withBursts.stream()
                .filter(s -> !s.bursts().isEmpty())
                .map(s -> Map.of(
                        "category", (Object) s.category(),
                        "severity", (Object) s.severity(),
                        "burstCount", (Object) s.bursts().size(),
                        "totalBurstIssues", (Object) s.bursts().stream().mapToInt(CriticalBurst::issueCount).sum(),
                        "firstStart", (Object) s.bursts().stream().map(CriticalBurst::burstStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).map(Object::toString).orElse(""),
                        "lastEnd", (Object) s.bursts().stream().map(CriticalBurst::burstEnd).filter(Objects::nonNull).max(Comparator.naturalOrder()).map(Object::toString).orElse("")
                ))
                .toList();
        return Response.ok(result).build();
    }

    @GET
    @Path("/{id}/critical-issues/bursts/{category}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCriticalBurstsByCategory(@PathParam("id") String id,
                                                @PathParam("category") String category,
                                                @QueryParam("page") @DefaultValue("0") int page,
                                                @QueryParam("size") @DefaultValue("10") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var withBursts = getOrComputeBursts(analysis);
        var categorySummary = withBursts.stream()
                .filter(s -> s.category().equalsIgnoreCase(category))
                .findFirst();
        if (categorySummary.isEmpty()) {
            return Response.ok(Map.of("data", List.of(), "total", 0, "page", 0, "size", size)).build();
        }
        var allBursts = categorySummary.get().bursts();
        int total = allBursts.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        var burstMetas = allBursts.subList(from, to).stream().map(b -> Map.of(
                "burstStart", (Object)(b.burstStart() != null ? b.burstStart().toString() : ""),
                "burstEnd", (Object)(b.burstEnd() != null ? b.burstEnd().toString() : ""),
                "issueCount", (Object) b.issueCount()
        )).toList();
        return Response.ok(Map.of("data", burstMetas, "total", total, "page", page, "size", size)).build();
    }

    @GET
    @Path("/{id}/critical-issues/bursts/{category}/{burstIndex}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCriticalBurstIssues(@PathParam("id") String id,
                                           @PathParam("category") String category,
                                           @PathParam("burstIndex") int burstIndex,
                                           @QueryParam("page") @DefaultValue("0") int page,
                                           @QueryParam("size") @DefaultValue("25") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var withBursts = getOrComputeBursts(analysis);
        var categorySummary = withBursts.stream()
                .filter(s -> s.category().equalsIgnoreCase(category))
                .findFirst();
        if (categorySummary.isEmpty() || burstIndex < 0 || burstIndex >= categorySummary.get().bursts().size()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Burst not found"))
                    .build();
        }
        var burst = categorySummary.get().bursts().get(burstIndex);
        return paginatedResponse(burst.issues(), page, size);
    }

    @GET
    @Path("/{id}/npe-analysis")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNpeAnalysis(@PathParam("id") String id,
                                   @QueryParam("page") @DefaultValue("0") int page,
                                   @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        // Return summaries without occurrences (fetched on-demand via detail endpoint)
        var stripped = analysis.getNpeAnalysis().stream()
                .map(s -> new NpeLocationSummary(s.origin(), s.originClass(), s.method(), s.sourceFile(), s.sourceLine(),
                        s.count(), s.firstSeen(), s.lastSeen(), List.of()))
                .toList();
        return paginatedResponse(stripped, page, size);
    }

    @GET
    @Path("/{id}/npe-analysis/{origin}/occurrences")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNpeOccurrences(@PathParam("id") String id,
                                      @PathParam("origin") String origin,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("25") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var summary = analysis.getNpeAnalysis().stream()
                .filter(s -> s.origin().equals(origin)).findFirst();
        if (summary.isEmpty()) return Response.ok(Map.of("data", List.of(), "total", 0, "page", 0, "size", size)).build();
        return paginatedResponse(summary.get().occurrences(), page, size);
    }

    @GET
    @Path("/{id}/exception-analysis")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getExceptionAnalysis(@PathParam("id") String id,
                                          @QueryParam("page") @DefaultValue("0") int page,
                                          @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var stripped = analysis.getExceptionAnalysis().stream()
                .map(s -> new ExceptionLocationSummary(s.exceptionType(), s.origin(), s.originClass(), s.method(), s.sourceFile(), s.sourceLine(),
                        s.count(), s.firstSeen(), s.lastSeen(), List.of()))
                .toList();
        return paginatedResponse(stripped, page, size);
    }

    @GET
    @Path("/{id}/exception-analysis/{origin}/occurrences")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getExceptionOccurrences(@PathParam("id") String id,
                                             @PathParam("origin") String origin,
                                             @QueryParam("page") @DefaultValue("0") int page,
                                             @QueryParam("size") @DefaultValue("25") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        var summary = analysis.getExceptionAnalysis().stream()
                .filter(s -> (s.exceptionType() + ":" + s.origin()).equals(origin) || s.origin().equals(origin)).findFirst();
        if (summary.isEmpty()) return Response.ok(Map.of("data", List.of(), "total", 0, "page", 0, "size", size)).build();
        return paginatedResponse(summary.get().occurrences(), page, size);
    }

    @GET
    @Path("/{id}/custom-fields/{fieldName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCustomFieldMatches(@PathParam("id") String id,
                                          @PathParam("fieldName") String fieldName,
                                          @QueryParam("page") @DefaultValue("0") int page,
                                          @QueryParam("size") @DefaultValue("100") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var result = analysis.getCustomFieldResults().stream()
                .filter(cf -> cf.fieldName().equals(fieldName))
                .findFirst();

        if (result.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Custom field not found."))
                    .build();
        }

        CustomFieldResult cfr = result.get();
        if (cfr.countOnly()) {
            return Response.ok(Map.of(
                    "fieldName", cfr.fieldName(),
                    "matchCount", cfr.matchCount(),
                    "countOnly", true,
                    "data", List.of(),
                    "total", 0,
                    "page", 0,
                    "size", size
            )).build();
        }

        return paginatedResponse(cfr.matches(), page, size);
    }

    @GET
    @Path("/{id}/anomaly-detection")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnomalyDetection(@PathParam("id") String id,
                                         @QueryParam("signalType") @DefaultValue("ERROR_COUNT") String signalType,
                                         @QueryParam("bucketSize") @DefaultValue("300") int bucketSize,
                                         @QueryParam("threshold") @DefaultValue("3.0") double threshold,
                                         @QueryParam("baselineWindow") @DefaultValue("8") int baselineWindow) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        // Validate signalType
        SignalType type;
        try {
            type = SignalType.valueOf(signalType);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid signal type: " + signalType))
                    .build();
        }

        // Clamp parameters
        bucketSize = Math.clamp(bucketSize, 60, 3600);
        threshold = Math.clamp(threshold, 1.5, 20.0);
        baselineWindow = Math.clamp(baselineWindow, 2, 50);

        // Extract signals
        List<Signal> signals = signalExtractor.extract(analysis.getAllLines(), type);

        if (analysis.getTimeRangeStart() == null || analysis.getTimeRangeEnd() == null) {
            return Response.ok(new AnomalyDetectionResponse(
                    signalType, bucketSize, threshold, 0, 0, 0, "", 0,
                    List.of(), List.of(), List.of()
            )).build();
        }

        // Aggregate into time buckets
        List<BucketStats> buckets = TimeBucketAggregator.aggregate(
                signals, bucketSize, analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());

        // Detect anomalies
        AnomalyDetectorService.DetectionResult detection = anomalyDetectorService.detect(
                buckets, threshold, baselineWindow, signalType);

        List<BucketStats> updatedBuckets = detection.buckets();
        List<AnomalyResult> anomalies = detection.anomalies();

        // Compute summary stats
        long peakValue = updatedBuckets.stream().mapToLong(BucketStats::count).max().orElse(0);
        String peakBucketLabel = updatedBuckets.stream()
                .max(Comparator.comparingLong(BucketStats::count))
                .map(BucketStats::bucketLabel)
                .orElse("");
        long p95Value = computeP95FromBuckets(updatedBuckets);

        // Correlation detection (extract all signal types and detect)
        List<CorrelatedAnomaly> correlations = List.of();
        if (!anomalies.isEmpty()) {
            Map<SignalType, List<Signal>> allSignals = signalExtractor.extractAll(analysis.getAllLines());
            Map<String, List<AnomalyResult>> allAnomaliesByType = new LinkedHashMap<>();
            allAnomaliesByType.put(signalType, anomalies);

            for (Map.Entry<SignalType, List<Signal>> entry : allSignals.entrySet()) {
                if (entry.getKey().name().equals(signalType)) continue;

                List<BucketStats> otherBuckets = TimeBucketAggregator.aggregate(
                        entry.getValue(), bucketSize,
                        analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());
                AnomalyDetectorService.DetectionResult otherDetection = anomalyDetectorService.detect(
                        otherBuckets, threshold, baselineWindow, entry.getKey().name());

                if (!otherDetection.anomalies().isEmpty()) {
                    allAnomaliesByType.put(entry.getKey().name(), otherDetection.anomalies());
                }
            }

            if (allAnomaliesByType.size() >= 2) {
                correlations = CorrelationDetector.detect(allAnomaliesByType, 6);
            }
        }

        return Response.ok(new AnomalyDetectionResponse(
                signalType, bucketSize, threshold,
                updatedBuckets.size(),
                (int) updatedBuckets.stream().filter(b -> "ANOMALY".equals(b.status())).count(),
                peakValue, peakBucketLabel, p95Value,
                updatedBuckets, anomalies, correlations
        )).build();
    }

    @GET
    @Path("/{id}/anomaly-detection/signal-types")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAvailableSignalTypes(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        List<SignalType> types = signalExtractor.detectAvailableTypes(analysis.getAllLines());
        List<Map<String, Object>> result = types.stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "ordinal", t.ordinal()
                ))
                .toList();
        return Response.ok(result).build();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listAnalyses() {
        if (!enabled) return featureDisabled();
        var summaries = analyzeLogFileUseCase.listAll().stream()
                .map(this::analysisSummaryMap)
                .toList();
        return Response.ok(summaries).build();
    }

    // ---- Helpers ----

    private Response featureDisabled() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "Log analyzer is disabled.")).build();
    }

    private Response analysisNotFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Analysis not found.")).build();
    }

    private Response paginatedResponse(List<?> all, int page, int size) {
        page = Math.max(0, page);
        size = Math.clamp(size, 1, 5000);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return Response.ok(Map.of("data", all.subList(from, to), "total", total, "page", page, "size", size)).build();
    }

    private List<CriticalIssueSummary> getOrComputeBursts(LogAnalysis analysis) {
        var cached = analysis.getCachedBursts();
        if (cached != null) return cached;
        var computed = criticalIssueDetector.computeBursts(analysis.getCriticalIssues());
        analysis.setCachedBursts(computed);
        return computed;
    }

    private long computeP95FromBuckets(List<BucketStats> buckets) {
        long[] counts = buckets.stream()
                .mapToLong(BucketStats::count)
                .filter(c -> c > 0)
                .sorted()
                .toArray();
        if (counts.length == 0) return 0;
        int idx = Math.max(0, (int) Math.ceil(counts.length * 0.95) - 1);
        return counts[idx];
    }

    private boolean matchesLevelGroup(String filter, String lineLevel) {
        if (lineLevel == null) return false;
        return switch (filter.toUpperCase()) {
            case "ERROR" -> "ERROR".equals(lineLevel) || "FATAL".equals(lineLevel) || "SEVERE".equals(lineLevel);
            case "WARN" -> "WARN".equals(lineLevel) || "WARNING".equals(lineLevel);
            default -> filter.equalsIgnoreCase(lineLevel);
        };
    }

    private Map<String, Object> analysisSummaryMap(LogAnalysis a) {
        var customFieldsSummary = a.getCustomFieldResults().stream()
                .map(cf -> Map.of("fieldName", (Object) cf.fieldName(), "matchCount", (Object) cf.matchCount(),
                        "countOnly", (Object) cf.countOnly()))
                .toList();

        int criticalIssueCount = a.getCriticalIssues().stream()
                .mapToInt(CriticalIssueSummary::count).sum();

        int npeAnalysisCount = a.getNpeAnalysis().stream()
                .mapToInt(NpeLocationSummary::count).sum();
        int npeLocationCount = a.getNpeAnalysis().size();

        int exceptionAnalysisCount = a.getExceptionAnalysis().stream()
                .mapToInt(ExceptionLocationSummary::count).sum();
        long exceptionTypeCount = a.getExceptionAnalysis().stream()
                .map(ExceptionLocationSummary::exceptionType)
                .distinct()
                .count();

        // burstCount not computed eagerly; use on-demand endpoint

        var criticalIssueSummaries = a.getCriticalIssues().stream()
                .map(s -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("category", s.category());
                    map.put("severity", s.severity());
                    map.put("count", s.count());
                    map.put("firstSeen", s.firstSeen() != null ? s.firstSeen().toString() : null);
                    map.put("lastSeen", s.lastSeen() != null ? s.lastSeen().toString() : null);
                    map.put("burstCount", s.bursts().size());
                    return map;
                })
                .toList();

        return Map.ofEntries(
                Map.entry("id", a.getId()),
                Map.entry("sourceFiles", a.getSourceFiles()),
                Map.entry("totalLineCount", a.getTotalLineCount()),
                Map.entry("uploadedAt", a.getUploadedAt().toString()),
                Map.entry("timeRangeStart", a.getTimeRangeStart() != null ? a.getTimeRangeStart().toString() : ""),
                Map.entry("timeRangeEnd", a.getTimeRangeEnd() != null ? a.getTimeRangeEnd().toString() : ""),
                Map.entry("threadCount", a.getThreads().size()),
                Map.entry("endpointCount", a.getEndpoints().size()),
                Map.entry("apiCallCount", a.getApiCalls().size()),
                Map.entry("errorCount", a.getErrors().size()),
                Map.entry("levelCounts", a.getLevelCounts()),
                Map.entry("jobExecutionCount", a.getJobExecutions().size()),
                Map.entry("repeatedFailureCount", a.getRepeatedFailures().size()),
                Map.entry("customFields", customFieldsSummary),
                Map.entry("criticalIssueCount", criticalIssueCount),
                Map.entry("criticalIssueSummaries", criticalIssueSummaries),
                Map.entry("npeAnalysisCount", npeAnalysisCount),
                Map.entry("npeLocationCount", npeLocationCount),
                Map.entry("exceptionAnalysisCount", exceptionAnalysisCount),
                Map.entry("exceptionTypeCount", exceptionTypeCount)
        );
    }

    private Map<String, Object> presetToMap(LogPreset p) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", p.name());
        map.put("logLineRegex", p.logLineRegex());
        map.put("timestampFormat", p.timestampFormat());
        map.put("apiCallRegex", p.apiCallRegex());
        map.put("jobStartRegex", p.jobStartRegex());
        map.put("jobEndRegex", p.jobEndRegex());
        map.put("failureRegex", p.failureRegex());
        map.put("sensitiveFieldNames", p.sensitiveFieldNames());
        map.put("customFields", p.customFields().stream()
                .map(cf -> Map.of("name", cf.name(), "regex", cf.regex(), "countOnly", cf.countOnly()))
                .toList());
        return map;
    }

    private String extractString(Map<String, List<InputPart>> form, String key) {
        List<InputPart> parts = form.get(key);
        if (parts == null || parts.isEmpty()) return null;
        try {
            return parts.getFirst().getBodyAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFilename(InputPart part) {
        String header = part.getHeaders().getFirst("Content-Disposition");
        if (header == null) return null;
        for (String s : header.split(";")) {
            String trimmed = s.trim();
            if (trimmed.startsWith("filename")) {
                return trimmed.split("=")[1].trim().replace("\"", "");
            }
        }
        return null;
    }

    private String nonBlankOrDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private List<LogPreset.CustomField> parseCustomFieldsJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            List<LogPreset.CustomField> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                String regex = (String) item.get("regex");
                boolean countOnly = Boolean.TRUE.equals(item.get("countOnly"));
                if (name != null && regex != null) {
                    result.add(new LogPreset.CustomField(name, regex, countOnly));
                }
            }
            return result;
        } catch (Exception e) {
            Log.warnf("Failed to parse customFields JSON: %s", e.getMessage());
            return List.of();
        }
    }

    private boolean containsIgnoreCase(ApiCallPair call, String search) {
        return containsIgnoreCase(call.requestPayload(), search)
                || containsIgnoreCase(call.responsePayload(), search)
                || containsIgnoreCase(call.correlationId(), search);
    }

    private boolean containsIgnoreCase(String text, String search) {
        if (text == null || text.length() < search.length()) return false;
        for (int i = 0, max = text.length() - search.length(); i <= max; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) return true;
        }
        return false;
    }

    private AnalysisOptions parseAnalysisOptions(String json) {
        if (json == null || json.isBlank()) return AnalysisOptions.all();
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            return buildAnalysisOptions(map);
        } catch (Exception e) {
            Log.warnf("Failed to parse analysis options JSON, using defaults: %s", e.getMessage());
            return AnalysisOptions.all();
        }
    }

    @SuppressWarnings("unchecked")
    private AnalysisOptions parseAnalysisOptionsFromMap(Object optionsObj) {
        if (optionsObj == null) return AnalysisOptions.all();
        if (optionsObj instanceof Map<?, ?> map) {
            return buildAnalysisOptions((Map<String, Object>) map);
        }
        return AnalysisOptions.all();
    }

    private AnalysisOptions buildAnalysisOptions(Map<String, Object> map) {
        return new AnalysisOptions(
                optionFlag(map, "apiCalls"),
                optionFlag(map, "jobs"),
                optionFlag(map, "failures"),
                optionFlag(map, "criticalIssues"),
                optionFlag(map, "npeAnalysis"),
                optionFlag(map, "exceptionAnalysis"),
                optionFlag(map, "customFields")
        );
    }

    private boolean optionFlag(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return !"false".equalsIgnoreCase(s);
        return true; // default to enabled
    }
}
