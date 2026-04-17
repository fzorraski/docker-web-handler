/**
 * Layer: interfaces/rest (presentation)
 * SOLID: S (single responsibility — HTTP routing and response mapping only, zero business logic),
 *        D (depends on application use cases, not infrastructure)
 * Behavior: identical to original — all logic moved to use cases and helpers
 */
package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.application.usecase.*;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.model.anomaly.*;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import br.com.fzdevx.interfaces.rest.util.AnalysisSummaryMapper;
import br.com.fzdevx.interfaces.rest.util.ContentDispositionHelper;
import br.com.fzdevx.interfaces.rest.util.UploadFormParser;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

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
    QueryApiCallsUseCase queryApiCallsUseCase;

    @Inject
    ExportApiStatsUseCase exportApiStatsUseCase;

    @Inject
    GetPerformanceInsightsUseCase getPerformanceInsightsUseCase;

    @Inject
    QueryLogLinesUseCase queryLogLinesUseCase;

    @Inject
    GetThreadSummaryUseCase getThreadSummaryUseCase;

    @Inject
    QueryJobExecutionsUseCase queryJobExecutionsUseCase;

    @Inject
    QueryOrphanRequestsUseCase queryOrphanRequestsUseCase;

    @Inject
    QueryCriticalIssuesUseCase queryCriticalIssuesUseCase;

    @Inject
    QueryNpeAnalysisUseCase queryNpeAnalysisUseCase;

    @Inject
    QueryExceptionAnalysisUseCase queryExceptionAnalysisUseCase;

    @Inject
    QueryCustomFieldsUseCase queryCustomFieldsUseCase;

    @Inject
    DetectAnomaliesUseCase detectAnomaliesUseCase;

    @Inject
    GetSystemHealthUseCase getSystemHealthUseCase;

    @Inject
    GenerateReportUseCase generateReportUseCase;

    @Inject
    br.com.fzdevx.interfaces.rest.util.LogAnalysisBroadcaster logAnalysisBroadcaster;

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
                "presets", logPresetProvider.allPresets().stream().map(AnalysisSummaryMapper::presetToMap).toList(),
                "defaultPreset", defaultPresetName,
                "containerTail", defaultContainerTail,
                "maxFiles", analyzeLogFileUseCase.getMaxFiles(),
                "currentFiles", analyzeLogFileUseCase.getAnalysisCount()
        )).build();
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadAndAnalyze(MultipartFormDataInput input) {
        if (!enabled) return featureDisabled();

        AnalyzeLogFileRequest request = null;
        try {
            request = parseUploadForm(input);
            var duplicates = analyzeLogFileUseCase.findDuplicateFilenames(
                    request.getFilenames(), logAnalysisBroadcaster.getActiveFilenames());
            if (!duplicates.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "File(s) already analyzed: " + String.join(", ", duplicates),
                                "duplicates", duplicates))
                        .build();
            }
            var result = analyzeLogFileUseCase.analyze(
                    request.getTempFiles(), request.getFilenames(),
                    request.getPreset(), request.getSlowThresholdMs(), request.getOptions());
            if (request.getLabel() != null && !request.getLabel().isBlank()) {
                String lbl = request.getLabel().trim();
                result.analysis().setLabel(lbl.length() > 50 ? lbl.substring(0, 50) : lbl);
            }
            if (result.evictedId() != null) {
                logAnalysisBroadcaster.broadcastDeleted(result.evictedId());
            }
            return Response.ok(AnalysisSummaryMapper.toSummaryMap(result.analysis())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            Log.errorf("Log analysis failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Log analysis failed. Please try again.")).build();
        } finally {
            if (request != null) request.cleanupTempFiles();
        }
    }

    /**
     * Parse multipart upload form into an AnalyzeLogFileRequest.
     * Shared between the synchronous upload and the SSE prepare endpoint.
     * Throws IllegalArgumentException for validation errors.
     */
    AnalyzeLogFileRequest parseUploadForm(MultipartFormDataInput input) throws Exception {
        return UploadFormParser.parse(input, logPresetProvider, defaultPresetName,
                defaultSlowThresholdMs, maxFileSizeMb);
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
            return Response.ok(AnalysisSummaryMapper.toSummaryMap(analysis)).build();
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
        int slowThresholdMsValue = slowObj instanceof Number n ? n.intValue() : defaultSlowThresholdMs;

        var options = UploadFormParser.parseAnalysisOptionsFromMap(body.get("options"));

        LogAnalysis composed = analyzeLogFileUseCase.compose(ids, preset, slowThresholdMsValue, options);
        if (composed == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No valid analyses found for the given IDs."))
                    .build();
        }
        return Response.ok(AnalysisSummaryMapper.toSummaryMap(composed)).build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysis(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();
        return Response.ok(AnalysisSummaryMapper.toSummaryMap(analysis)).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAnalysis(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        boolean deleted = analyzeLogFileUseCase.delete(id);
        if (!deleted) return analysisNotFound();
        logAnalysisBroadcaster.broadcastDeleted(id);
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
                                @QueryParam("timeFrom") String timeFromStr,
                                @QueryParam("timeTo") String timeToStr,
                                @QueryParam("sort") @DefaultValue("time") String sort,
                                @QueryParam("sortDir") @DefaultValue("asc") String sortDir,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        LocalDateTime timeFrom = parseDateTime(timeFromStr);
        LocalDateTime timeTo = parseDateTime(timeToStr);

        var result = queryApiCallsUseCase.execute(analysis.getApiCalls(),
                endpoint, thread, minDuration, search, timeFrom, timeTo, sort, sortDir, page, size);
        return Response.ok(result.toMap()).build();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
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
    @Path("/{id}/api-stats/export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response exportApiStats(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var export = exportApiStatsUseCase.execute(analysis);
        String filename = exportApiStatsUseCase.buildFilename(analysis);
        return Response.ok(export, MediaType.APPLICATION_JSON)
                .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader(filename))
                .build();
    }

    @GET
    @Path("/{id}/performance-insights")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPerformanceInsights(@PathParam("id") String id,
                                           @QueryParam("endpoint") String endpoint) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        PerformanceInsights insights = getPerformanceInsightsUseCase.computeInsights(
                analysis.getApiCalls(), endpoint,
                analysis.getTimeRangeStart(), analysis.getTimeRangeEnd());
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
        try {
            var bucketEndpoints = getPerformanceInsightsUseCase.computeBucketEndpoints(
                    analysis.getApiCalls(), endpoint,
                    analysis.getTimeRangeStart(), analysis.getTimeRangeEnd(),
                    timestamp, limit);
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

        var result = queryLogLinesUseCase.queryLines(analysis.getAllLines(), thread, level, search, page, size);
        return Response.ok(result.toMap()).build();
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

        var result = queryLogLinesUseCase.queryLineRange(analysis.getAllLines(), from, to, level, page, size);
        return Response.ok(result.toMap()).build();
    }

    @GET
    @Path("/{id}/threads")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getThreads(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        return Response.ok(getThreadSummaryUseCase.execute(analysis.getAllLines())).build();
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
                            @QueryParam("jobName") String jobName,
                            @QueryParam("thread") String thread,
                            @QueryParam("sort") @DefaultValue("time") String sort,
                            @QueryParam("page") @DefaultValue("0") int page,
                            @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var result = queryJobExecutionsUseCase.query(analysis.getJobExecutions(), jobName, thread, sort, page, size);
        return Response.ok(result.toMap()).build();
    }

    @GET
    @Path("/{id}/jobs/filters")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getJobFilters(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        return Response.ok(queryJobExecutionsUseCase.getFilters(analysis.getJobExecutions())).build();
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
        return Response.ok(PaginatedResult.of(analysis.getRepeatedFailures(), page, size).toMap()).build();
    }

    @GET
    @Path("/{id}/orphan-requests")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOrphanRequests(@PathParam("id") String id,
                                      @QueryParam("endpoint") String endpoint,
                                      @QueryParam("thread") String thread,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("50") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var result = queryOrphanRequestsUseCase.execute(analysis.getOrphanRequests(), endpoint, thread, page, size);
        return Response.ok(result.toMap()).build();
    }

    @GET
    @Path("/{id}/critical-issues")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCriticalIssues(@PathParam("id") String id,
                                      @QueryParam("category") String category) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        return Response.ok(queryCriticalIssuesUseCase.queryByCategory(analysis.getCriticalIssues(), category)).build();
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

        return Response.ok(queryCriticalIssuesUseCase.getBurstSummaries(analysis, threshold, windowMinutes)).build();
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

        var result = queryCriticalIssuesUseCase.getBurstsByCategory(analysis, category, page, size);
        return Response.ok(result.toMap()).build();
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

        var result = queryCriticalIssuesUseCase.getBurstIssues(analysis, category, burstIndex, page, size);
        if (result.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Burst not found"))
                    .build();
        }
        return Response.ok(result.get().toMap()).build();
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

        var result = queryNpeAnalysisUseCase.querySummaries(analysis.getNpeAnalysis(), page, size);
        return Response.ok(result.toMap()).build();
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

        var result = queryNpeAnalysisUseCase.queryOccurrences(analysis.getNpeAnalysis(), origin, page, size);
        return Response.ok(result.toMap()).build();
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

        var result = queryExceptionAnalysisUseCase.querySummaries(analysis.getExceptionAnalysis(), page, size);
        return Response.ok(result.toMap()).build();
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

        var result = queryExceptionAnalysisUseCase.queryOccurrences(analysis.getExceptionAnalysis(), origin, page, size);
        return Response.ok(result.toMap()).build();
    }

    @GET
    @Path("/{id}/custom-fields/{fieldName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCustomFieldMatches(@PathParam("id") String id,
                                          @PathParam("fieldName") String fieldName,
                                          @QueryParam("search") String search,
                                          @QueryParam("thread") String thread,
                                          @QueryParam("sort") String sort,
                                          @QueryParam("sortDir") @DefaultValue("asc") String sortDir,
                                          @QueryParam("page") @DefaultValue("0") int page,
                                          @QueryParam("size") @DefaultValue("100") int size) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var result = queryCustomFieldsUseCase.execute(analysis.getCustomFieldResults(),
                fieldName, search, thread, sort, sortDir, page, size);

        if (!result.found()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Custom field not found."))
                    .build();
        }

        if (result.countOnly()) {
            return Response.ok(Map.of(
                    "fieldName", result.fieldName(),
                    "matchCount", result.matchCount(),
                    "countOnly", true,
                    "data", List.of(),
                    "total", 0,
                    "page", 0,
                    "size", size
            )).build();
        }

        return Response.ok(result.paginated().toMap()).build();
    }

    @GET
    @Path("/{id}/anomaly-detection")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnomalyDetection(@PathParam("id") String id,
                                         @QueryParam("signalType") @DefaultValue("ERROR_COUNT") String signalType,
                                         @QueryParam("bucketSize") @DefaultValue("300") int bucketSize,
                                         @QueryParam("threshold") @DefaultValue("3.0") double threshold,
                                         @QueryParam("baselineWindow") @DefaultValue("8") int baselineWindow,
                                         @QueryParam("metric") @DefaultValue("count") String metric,
                                         @QueryParam("method") @DefaultValue("ratio") String method) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        // Validate signalType
        SignalType parsedSignalType;
        try {
            parsedSignalType = SignalType.valueOf(signalType);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid signal type: " + signalType))
                    .build();
        }

        // Validate metric and method
        if (!DetectAnomaliesUseCase.VALID_METRICS.contains(metric)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid metric: " + metric + ". Valid: count, p95, max, avg"))
                    .build();
        }
        if (!detectAnomaliesUseCase.getValidMethods().contains(method)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid method: " + method + ". Valid: ratio, zscore"))
                    .build();
        }

        // Clamp parameters
        bucketSize = Math.clamp(bucketSize, 60, 3600);
        threshold = Math.clamp(threshold, 1.5, 20.0);
        baselineWindow = Math.clamp(baselineWindow, 2, 50);

        AnomalyDetectionResponse response = detectAnomaliesUseCase.detect(
                analysis, parsedSignalType, bucketSize, threshold, baselineWindow, metric, method);
        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}/anomaly-detection/signal-types")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAvailableSignalTypes(@PathParam("id") String id) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        return Response.ok(detectAnomaliesUseCase.getAvailableSignalTypes(analysis)).build();
    }

    @GET
    @Path("/{id}/system-health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSystemHealth(@PathParam("id") String id,
                                     @QueryParam("bucketSize") @DefaultValue("300") int bucketSize,
                                     @QueryParam("metric") @DefaultValue("count") String metric) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        if (!DetectAnomaliesUseCase.VALID_METRICS.contains(metric)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid metric: " + metric)).build();
        }
        bucketSize = Math.clamp(bucketSize, 60, 3600);

        return Response.ok(getSystemHealthUseCase.execute(analysis, bucketSize, metric)).build();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listAnalyses() {
        if (!enabled) return featureDisabled();
        var summaries = analyzeLogFileUseCase.listAll().stream()
                .map(AnalysisSummaryMapper::toSummaryMap)
                .toList();
        return Response.ok(summaries).build();
    }

    // ── Stats Comparison ─────────────────────────────────────────────────────

    @POST
    @Path("/compare-stats")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("text/html")
    public Response compareStats(Map<String, Object> body) {
        if (!enabled) return featureDisabled();
        try {
            String labelA = (String) body.getOrDefault("labelA", "A");
            String labelB = (String) body.getOrDefault("labelB", "B");
            List<EndpointStats> statsA = OBJECT_MAPPER.convertValue(body.get("endpointsA"), new TypeReference<>() {});
            List<EndpointStats> statsB = OBJECT_MAPPER.convertValue(body.get("endpointsB"), new TypeReference<>() {});
            String html = generateReportUseCase.generateComparison(labelA, labelB, statsA, statsB);
            return Response.ok(html, "text/html")
                    .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader("stats-comparison.html"))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid comparison data: " + e.getMessage())).build();
        }
    }

    // ── Report Download ──────────────────────────────────────────────────────

    @GET
    @Path("/{id}/report/{type}")
    @Produces("text/html")
    public Response downloadReport(@PathParam("id") String id,
                                   @PathParam("type") String type) {
        if (!enabled) return featureDisabled();
        LogAnalysis analysis = analyzeLogFileUseCase.get(id);
        if (analysis == null) return analysisNotFound();

        var html = generateReportUseCase.generateReport(analysis, type);
        if (html.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid report type. Use 'compact' or 'complete'.")).build();
        }

        String filename = generateReportUseCase.buildReportFilename(analysis, type);
        return Response.ok(html.get(), "text/html")
                .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader(filename))
                .build();
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

}
