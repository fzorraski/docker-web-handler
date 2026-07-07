package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.*;
import br.com.fzdevx.application.usecase.AnalyzeContainerLogsUseCase;
import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.domain.model.anomaly.*;
import br.com.fzdevx.interfaces.rest.dto.*;
import br.com.fzdevx.application.port.AnomalyDetectionPort;
import br.com.fzdevx.application.port.CriticalBurstPort;
import br.com.fzdevx.application.port.ReportGeneratorPort;
import br.com.fzdevx.application.port.SignalExtractionPort;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogAnalyzerControllerTest {

    private static final String ANALYSIS_ID = "test-analysis-id";

    @Mock
    AnalyzeLogFileUseCase analyzeLogFileUseCase;

    @Mock
    AnalyzeContainerLogsUseCase analyzeContainerLogsUseCase;

    @Mock
    LogPresetProvider logPresetProvider;

    @Mock
    SignalExtractionPort signalExtractor;

    @Mock
    AnomalyDetectionPort anomalyDetectorService;

    @Mock
    CriticalBurstPort criticalIssueDetector;

    @Mock
    ReportGeneratorPort reportGeneratorPort;

    @Mock
    br.com.fzdevx.interfaces.rest.util.LogAnalysisBroadcaster logAnalysisBroadcaster;

    @Mock
    br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    // Use cases with no dependencies — @Spy creates real instances
    @Spy QueryApiCallsUseCase queryApiCallsUseCase;
    @Spy ExportApiStatsUseCase exportApiStatsUseCase;
    @Spy GetPerformanceInsightsUseCase getPerformanceInsightsUseCase;
    @Spy QueryLogLinesUseCase queryLogLinesUseCase;
    @Spy GetThreadSummaryUseCase getThreadSummaryUseCase;
    @Spy QueryJobExecutionsUseCase queryJobExecutionsUseCase;
    @Spy QueryOrphanRequestsUseCase queryOrphanRequestsUseCase;
    @Spy QueryNpeAnalysisUseCase queryNpeAnalysisUseCase;
    @Spy QueryExceptionAnalysisUseCase queryExceptionAnalysisUseCase;
    @Spy QueryCustomFieldsUseCase queryCustomFieldsUseCase;

    // Use cases with dependencies — @Spy, deps injected in setUp
    @Spy GenerateReportUseCase generateReportUseCase;
    @Spy QueryCriticalIssuesUseCase queryCriticalIssuesUseCase;
    @Spy DetectAnomaliesUseCase detectAnomaliesUseCase;
    @Spy GetSystemHealthUseCase getSystemHealthUseCase;

    @InjectMocks
    LogAnalyzerController controller;

    @BeforeEach
    void setUp() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(true);
        setField("maxFileSizeMb", 500);
        setField("defaultSlowThresholdMs", 1000);
        setField("defaultPresetName", "WILDFLY");
        setField("defaultContainerTail", 10000);
        setField("payloadTruncateThreshold", 102400);
        when(logPresetProvider.allPresets()).thenReturn(LogPreset.allPresets());
        when(logPresetProvider.byName(anyString())).thenAnswer(inv -> LogPreset.byName(inv.getArgument(0)));
        when(anomalyDetectorService.validMethods()).thenReturn(java.util.Set.of("ratio", "zscore"));

        // Inject mock dependencies into use cases that have their own @Inject fields
        setFieldOn(queryCriticalIssuesUseCase, "criticalBurstPort", criticalIssueDetector);
        setFieldOn(detectAnomaliesUseCase, "signalExtractor", signalExtractor);
        setFieldOn(detectAnomaliesUseCase, "anomalyDetector", anomalyDetectorService);
        setFieldOn(getSystemHealthUseCase, "signalExtractor", signalExtractor);
        setFieldOn(generateReportUseCase, "reportGenerator", reportGeneratorPort);

        // Delegate report generation to HtmlReportGenerator for tests that assert HTML output
        when(reportGeneratorPort.generateCompact(any())).thenAnswer(inv ->
                br.com.fzdevx.infrastructure.log.HtmlReportGenerator.generateCompact(inv.getArgument(0)));
        when(reportGeneratorPort.generateComplete(any())).thenAnswer(inv ->
                br.com.fzdevx.infrastructure.log.HtmlReportGenerator.generateComplete(inv.getArgument(0)));
        when(reportGeneratorPort.generateComparison(anyString(), anyString(), any(), any())).thenAnswer(inv ->
                br.com.fzdevx.infrastructure.log.HtmlReportGenerator.generateComparison(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
    }

    private void setField(String name, Object value) {
        try {
            Field f = LogAnalyzerController.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(controller, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setFieldOn(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Helper: build a sample LogAnalysis ----

    private LogAnalysis buildSampleAnalysis() {
        var sourceFiles = List.of(
                new LogAnalysis.SourceFile("server.log", 1024),
                new LogAnalysis.SourceFile("server2.log", 2048)
        );

        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);

        var lines = List.of(
                new LogLine(1, now, "INFO", "com.example.App", "main", "Application started", "server.log"),
                new LogLine(2, now.plusSeconds(1), "WARN", "com.example.Db", "db-pool-1", "Slow query detected", "server.log"),
                new LogLine(3, now.plusSeconds(2), "ERROR", "com.example.Api", "http-thread-1", "NullPointerException", "server.log"),
                new LogLine(4, now.plusSeconds(3), "INFO", "com.example.Api", "http-thread-1", "Request processed", "server2.log")
        );

        var apiCalls = List.of(
                new ApiCallPair("UserResource/getUser", "101", "http-thread-1",
                        now, now.plusSeconds(1), 150, -1, false, "{}", "{\"id\":1}", 1, 2, "server.log", false),
                new ApiCallPair("OrderResource/create", "102", "http-thread-2",
                        now.plusSeconds(2), now.plusSeconds(5), 3000, -1, false, "{\"item\":1}", "{\"id\":2}", 3, 4, "server.log", true)
        );

        var endpointStats = List.of(
                new EndpointStats("UserResource/getUser", 1, 150.0, 150, 150, 150, 0, -1, -1, 0),
                new EndpointStats("OrderResource/create", 1, 3000.0, 3000, 3000, 3000, 1, -1, -1, 0)
        );

        var levelCounts = Map.of("INFO", 2, "WARN", 1, "ERROR", 1);

        var errors = List.of(lines.get(2));

        var jobExecutions = List.of(
                new JobExecution("CleanupJob", "dailyTrigger", "scheduler-1",
                        now, now.plusSeconds(10), 10000, "SUCCESS", 10, 20, "server.log")
        );

        var repeatedFailures = List.of(
                new RepeatedFailure("ORDER 123", "TIMEOUT", 3,
                        now, now.plusSeconds(30),
                        List.of(new RepeatedFailure.FailureDetail(now, 5, "Timeout occurred", "server.log")))
        );

        var threads = List.of("main", "db-pool-1", "http-thread-1", "http-thread-2", "scheduler-1");
        var endpoints = List.of("UserResource/getUser", "OrderResource/create");

        var analysis = new LogAnalysis(sourceFiles, lines.size(), now, now.plusSeconds(30),
                threads, endpoints, apiCalls, endpointStats, levelCounts, errors,
                jobExecutions, repeatedFailures, lines);
        analysis.setCustomFieldResults(List.of(
                new CustomFieldResult("Entity Changes", 3, false, List.of(
                        new CustomFieldMatch(10, now, "main", "server.log", "Updated -> User: data",
                                Map.of("entity", "User", "user", "admin")),
                        new CustomFieldMatch(20, now.plusSeconds(1), "main", "server.log", "Updated -> Config: data",
                                Map.of("entity", "Config", "user", "admin")),
                        new CustomFieldMatch(30, now.plusSeconds(2), "main", "server.log", "Updated -> Role: data",
                                Map.of("entity", "Role", "user", "system"))
                )),
                new CustomFieldResult("Error Codes", 5, true, List.of())
        ));
        return analysis;
    }

    private LogAnalysis buildMultiJobAnalysis() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var jobExecutions = List.of(
                new JobExecution("CleanupJob", "dailyTrigger", "scheduler-1",
                        now, now.plusSeconds(10), 10000, "SUCCESS", 10, 20, "server.log"),
                new JobExecution("BackupJob", "nightlyTrigger", "scheduler-2",
                        now.plusSeconds(20), now.plusSeconds(25), 5000, "SUCCESS", 30, 40, "server.log"),
                new JobExecution("SyncJob", "hourlyTrigger", "scheduler-1",
                        now.plusSeconds(40), now.plusSeconds(60), 20000, "FAILED", 50, 60, "server.log")
        );
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                4, now, now.plusSeconds(60),
                List.of("scheduler-1", "scheduler-2"), List.of(),
                List.of(), List.of(), Map.of("INFO", 4), List.of(),
                jobExecutions, List.of(), List.of()
        );
        return analysis;
    }

    private LogAnalysis buildAnalysisWithOrphans() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var orphans = List.of(
                new OrphanRequest("UserWS/getUser", "http-thread-1", now, "{\"id\":1}", 10, "server.log"),
                new OrphanRequest("OrderWS/create", "http-thread-2", now.plusSeconds(5), "{\"item\":1}", 20, "server.log")
        );
        return new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                4, now, now.plusSeconds(30),
                List.of("http-thread-1", "http-thread-2"), List.of(),
                List.of(), List.of(), Map.of("INFO", 4), List.of(),
                List.of(), List.of(), List.of(), orphans, List.of()
        );
    }

    // ---- Helper: assert error response ----

    @SuppressWarnings("unchecked")
    private void assertErrorContains(Response response, String substring) {
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        String error = (String) entity.get("error");
        assertTrue(error.toLowerCase().contains(substring.toLowerCase()),
                "Expected error to contain '" + substring + "' but got: " + error);
    }

    // ---- Multipart mock helpers ----

    private InputPart mockStringPart(String value) throws Exception {
        InputPart part = mock(InputPart.class);
        when(part.getBodyAsString()).thenReturn(value);
        return part;
    }

    private InputPart mockFilePart(String filename, byte[] content) throws Exception {
        InputPart part = mock(InputPart.class);
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Content-Disposition", "form-data; name=\"files\"; filename=\"" + filename + "\"");
        when(part.getHeaders()).thenReturn(headers);
        when(part.getBody(eq(java.io.InputStream.class), any())).thenReturn(new ByteArrayInputStream(content));
        return part;
    }

    private MultipartFormDataInput mockUploadForm(String presetName, String filename, byte[] content) throws Exception {
        MultipartFormDataInput input = mock(MultipartFormDataInput.class);
        Map<String, List<InputPart>> form = new HashMap<>();
        form.put("preset", List.of(mockStringPart(presetName)));
        form.put("files", List.of(mockFilePart(filename, content)));
        when(input.getFormDataMap()).thenReturn(form);
        return input;
    }

    private MultipartFormDataInput mockUploadFormNoFiles(String presetName) throws Exception {
        MultipartFormDataInput input = mock(MultipartFormDataInput.class);
        Map<String, List<InputPart>> form = new HashMap<>();
        form.put("preset", List.of(mockStringPart(presetName)));
        when(input.getFormDataMap()).thenReturn(form);
        return input;
    }

    private MultipartFormDataInput mockUploadFormWithOverrides(String presetName, String logLineRegex) throws Exception {
        MultipartFormDataInput input = mock(MultipartFormDataInput.class);
        Map<String, List<InputPart>> form = new HashMap<>();
        form.put("preset", List.of(mockStringPart(presetName)));
        if (logLineRegex != null) {
            form.put("logLineRegex", List.of(mockStringPart(logLineRegex)));
        }
        form.put("files", List.of(mockFilePart("test.log", "log content".getBytes())));
        when(input.getFormDataMap()).thenReturn(form);
        return input;
    }

    // ======================================================================
    // 1. Feature toggle tests
    // ======================================================================

    @Test
    void getStatus_returnsPresetsAndEnabledFlag() {
        Response response = controller.getStatus();

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(true, entity.get("enabled"));
        assertNotNull(entity.get("presets"));
        @SuppressWarnings("unchecked")
        List<?> presets = (List<?>) entity.get("presets");
        assertFalse(presets.isEmpty());
        assertEquals("WILDFLY", entity.get("defaultPreset"));
    }

    @Test
    void upload_disabled_returnsForbidden() throws Exception {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.uploadAndAnalyze(mock(MultipartFormDataInput.class));

        assertEquals(403, response.getStatus());
    }

    @Test
    void getAnalysis_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getAnalysis(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteAnalysis_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.deleteAnalysis(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getApiCalls_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getApiCalls(ANALYSIS_ID, null, null, null, null, false, false, null, null, null, null, null, null, "time", "asc", 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getApiStats_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getApiStats(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getLines_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getLines(ANALYSIS_ID, null, null, null, null, 0, 100);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getThreads_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getThreads(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getEndpoints_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getEndpoints(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getJobs_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getJobs(ANALYSIS_ID, null, null, null, "time", "asc", 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getFailures_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getFailures(ANALYSIS_ID, 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void listAnalyses_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.listAnalyses();

        assertEquals(403, response.getStatus());
    }

    @Test
    void compose_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.compose(Map.of("ids", List.of("a", "b")));

        assertEquals(403, response.getStatus());
    }

    // ======================================================================
    // 2. Not found tests
    // ======================================================================

    @Test
    void getAnalysis_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getAnalysis("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void deleteAnalysis_notFound_returns404() {
        when(analyzeLogFileUseCase.delete("nonexistent")).thenReturn(false);

        Response response = controller.deleteAnalysis("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getApiCalls_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getApiCalls("nonexistent", null, null, null, null, false, false, null, null, null, null, null, null, "time", "asc", 0, 50);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getApiStats_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getApiStats("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getLines_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getLines("nonexistent", null, null, null, null, 0, 100);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getThreads_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getThreads("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getEndpoints_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getEndpoints("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getJobs_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getJobs("nonexistent", null, null, null, "time", "asc", 0, 50);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getFailures_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getFailures("nonexistent", 0, 50);

        assertEquals(404, response.getStatus());
    }

    // ======================================================================
    // 3. Happy path tests
    // ======================================================================

    @Test
    void getAnalysis_found_returns200() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getAnalysis(analysis.getId());

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(analysis.getId(), entity.get("id"));
        assertEquals(4, entity.get("totalLineCount"));
        assertEquals(2, entity.get("apiCallCount"));
        assertEquals(2, entity.get("endpointCount"));
        assertEquals(5, entity.get("threadCount"));
        assertEquals(1, entity.get("errorCount"));
        assertEquals(1, entity.get("jobExecutionCount"));
        assertEquals(1, entity.get("repeatedFailureCount"));
    }

    @Test
    void deleteAnalysis_found_returns200() {
        when(analyzeLogFileUseCase.delete(ANALYSIS_ID)).thenReturn(true);

        Response response = controller.deleteAnalysis(ANALYSIS_ID);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(true, entity.get("deleted"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_found_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(2, entity.get("total"));
        assertEquals(0, entity.get("page"));
        assertEquals(50, entity.get("size"));
    }

    // ---- API calls content search ----

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchByRequestPayload_filtersMatches() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "item", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals(1, entity.get("total"));
        ApiCallPairResponse match = (ApiCallPairResponse) data.getFirst();
        assertEquals("OrderResource/create", match.endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchByResponsePayload_filtersMatches() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "\"id\":1", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        ApiCallPairResponse match = (ApiCallPairResponse) data.getFirst();
        assertEquals("UserResource/getUser", match.endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchByCorrelationId_filtersMatches() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "102", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        ApiCallPairResponse match = (ApiCallPairResponse) data.getFirst();
        assertEquals("OrderResource/create", match.endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchCaseInsensitive_filtersMatches() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "ITEM", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(1, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchNoMatch_returnsEmpty() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "nonexistent-value", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertTrue(data.isEmpty());
        assertEquals(0, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchCombinedWithEndpointFilter() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // search "id" matches both calls, but endpoint filter narrows to one
        Response response = controller.getApiCalls(analysis.getId(), "UserResource/getUser", null, null, null, false, false, null, null, "id", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        ApiCallPairResponse match = (ApiCallPairResponse) data.getFirst();
        assertEquals("UserResource/getUser", match.endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchBlank_returnsAll() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, "   ", null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(2, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_searchNull_returnsAll() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(2, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiStats_found_returns200() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiStats(analysis.getId());

        assertEquals(200, response.getStatus());
        List<?> entity = (List<?>) response.getEntity();
        assertEquals(2, entity.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_found_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, null, 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(4, data.size());
        assertEquals(4, entity.get("total"));
        assertEquals(0, entity.get("page"));
        assertEquals(100, entity.get("size"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getThreads_found_returns200() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getThreads(analysis.getId());

        assertEquals(200, response.getStatus());
        List<Map<String, Object>> entity = (List<Map<String, Object>>) response.getEntity();
        assertFalse(entity.isEmpty());
        // Threads with non-null thread field in lines
        for (Map<String, Object> threadEntry : entity) {
            assertNotNull(threadEntry.get("thread"));
            assertNotNull(threadEntry.get("lineCount"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void getEndpoints_found_returns200() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getEndpoints(analysis.getId());

        assertEquals(200, response.getStatus());
        List<String> entity = (List<String>) response.getEntity();
        assertEquals(2, entity.size());
        assertTrue(entity.contains("UserResource/getUser"));
        assertTrue(entity.contains("OrderResource/create"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_found_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobs(analysis.getId(), null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals(1, entity.get("total"));
        assertEquals(0, entity.get("page"));
        assertEquals(50, entity.get("size"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_filterByJobName_returnsOnlyMatching() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobs(analysis.getId(), "BackupJob", null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<JobExecution> data = (List<JobExecution>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("BackupJob", data.getFirst().jobName());
        assertEquals(1, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_filterByThread_returnsOnlyMatching() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobs(analysis.getId(), null, "scheduler-2", null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<JobExecution> data = (List<JobExecution>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("scheduler-2", data.getFirst().thread());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_sortByDuration_returnsDescending() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobs(analysis.getId(), null, null, null, "duration", "desc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<JobExecution> data = (List<JobExecution>) entity.get("data");
        assertEquals(3, data.size());
        assertTrue(data.get(0).durationMs() >= data.get(1).durationMs());
        assertTrue(data.get(1).durationMs() >= data.get(2).durationMs());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_sortByName_returnsAlphabetical() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobs(analysis.getId(), null, null, null, "name", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<JobExecution> data = (List<JobExecution>) entity.get("data");
        assertEquals(3, data.size());
        assertEquals("BackupJob", data.get(0).jobName());
        assertEquals("CleanupJob", data.get(1).jobName());
        assertEquals("SyncJob", data.get(2).jobName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobs_filterAndSort_combined() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // Filter by scheduler-1 (has CleanupJob and SyncJob), sort by duration
        Response response = controller.getJobs(analysis.getId(), null, "scheduler-1", null, "duration", "desc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<JobExecution> data = (List<JobExecution>) entity.get("data");
        assertEquals(2, data.size());
        assertTrue(data.get(0).durationMs() >= data.get(1).durationMs());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getJobFilters_returnsDistinctSortedNamesAndThreads() {
        LogAnalysis analysis = buildMultiJobAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getJobFilters(analysis.getId());

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<String> jobNames = (List<String>) entity.get("jobNames");
        List<String> threads = (List<String>) entity.get("threads");
        assertEquals(List.of("BackupJob", "CleanupJob", "SyncJob"), jobNames);
        assertEquals(List.of("scheduler-1", "scheduler-2"), threads);
    }

    @Test
    void getJobFilters_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getJobFilters(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getJobFilters_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getJobFilters("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFailures_found_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getFailures(analysis.getId(), 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals(1, entity.get("total"));
        assertEquals(0, entity.get("page"));
        assertEquals(50, entity.get("size"));
    }

    // ---- Orphan Requests ----

    @Test
    void getOrphanRequests_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getOrphanRequests(ANALYSIS_ID, null, null, 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getOrphanRequests_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getOrphanRequests("nonexistent", null, null, 0, 50);

        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrphanRequests_found_returns200WithPagination() {
        LogAnalysis analysis = buildAnalysisWithOrphans();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getOrphanRequests(analysis.getId(), null, null, 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(2, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrphanRequests_filterByEndpoint() {
        LogAnalysis analysis = buildAnalysisWithOrphans();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getOrphanRequests(analysis.getId(), "OrderWS/create", null, 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<OrphanRequestResponse> data = (List<OrphanRequestResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("OrderWS/create", data.getFirst().endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getOrphanRequests_filterByThread() {
        LogAnalysis analysis = buildAnalysisWithOrphans();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getOrphanRequests(analysis.getId(), null, "http-thread-2", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<OrphanRequestResponse> data = (List<OrphanRequestResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("http-thread-2", data.getFirst().thread());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAnalyses_returns200() {
        LogAnalysis analysis1 = buildSampleAnalysis();
        LogAnalysis analysis2 = buildSampleAnalysis();
        when(analyzeLogFileUseCase.listAll()).thenReturn(List.of(analysis1, analysis2));

        Response response = controller.listAnalyses();

        assertEquals(200, response.getStatus());
        List<?> entity = (List<?>) response.getEntity();
        assertEquals(2, entity.size());
    }

    // ======================================================================
    // 4. Compose tests
    // ======================================================================

    @Test
    void compose_lessThan2Ids_returnsBadRequest() {
        Response response = controller.compose(Map.of("ids", List.of("single-id")));

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "at least 2");
    }

    @Test
    void compose_noValidIds_returns404() {
        when(analyzeLogFileUseCase.compose(anyList(), any(LogPreset.class), anyInt())).thenReturn(null);

        Response response = controller.compose(Map.of("ids", List.of("id1", "id2")));

        assertEquals(404, response.getStatus());
        assertErrorContains(response, "no valid analyses");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compose_valid_returns200() {
        LogAnalysis composed = buildSampleAnalysis();
        when(analyzeLogFileUseCase.compose(anyList(), any(LogPreset.class), anyInt(), any())).thenReturn(composed);

        Response response = controller.compose(Map.of("ids", List.of("id1", "id2")));

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(composed.getId(), entity.get("id"));
        assertEquals(4, entity.get("totalLineCount"));
    }

    // ======================================================================
    // 5. Container log analysis
    // ======================================================================

    @Test
    void analyzeContainerLogs_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.analyzeContainerLogs("abcdef123456", null, null, null, null, "tail");
        assertEquals(403, response.getStatus());
    }

    @Test
    void analyzeContainerLogs_invalidContainerId_returnsBadRequest() {
        Response response = controller.analyzeContainerLogs("not-hex!", null, null, null, null, "tail");
        assertEquals(400, response.getStatus());
    }

    @Test
    void analyzeContainerLogs_success_returns200() throws Exception {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeContainerLogsUseCase.execute(anyString(), any(), anyInt(), anyString(), any(), anyInt()))
                .thenReturn(analysis);

        Response response = controller.analyzeContainerLogs("abcdef123456", null, null, null, null, "tail");

        assertEquals(200, response.getStatus());
        verify(analyzeContainerLogsUseCase).execute(eq("abcdef123456"), any(), eq(10000), eq("tail"), any(LogPreset.class), eq(1000));
    }

    @Test
    void analyzeContainerLogs_withCustomLines_clampsToRange() throws Exception {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeContainerLogsUseCase.execute(anyString(), any(), anyInt(), anyString(), any(), anyInt()))
                .thenReturn(analysis);

        Response response = controller.analyzeContainerLogs("abcdef123456", null, null, null, 50, "tail");

        assertEquals(200, response.getStatus());
        verify(analyzeContainerLogsUseCase).execute(eq("abcdef123456"), any(), eq(100), eq("tail"), any(), anyInt());
    }

    @Test
    void analyzeContainerLogs_containerLogException_returnsBadRequest() throws Exception {
        when(analyzeContainerLogsUseCase.execute(anyString(), any(), anyInt(), anyString(), any(), anyInt()))
                .thenThrow(new AnalyzeContainerLogsUseCase.ContainerLogException("Logging driver not supported"));

        Response response = controller.analyzeContainerLogs("abcdef123456", null, null, null, null, "tail");

        assertEquals(400, response.getStatus());
    }

    @Test
    void analyzeContainerLogs_unexpectedException_returns500() throws Exception {
        when(analyzeContainerLogsUseCase.execute(anyString(), any(), anyInt(), anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("unexpected"));

        Response response = controller.analyzeContainerLogs("abcdef123456", null, null, null, null, "tail");

        assertEquals(500, response.getStatus());
    }

    // ======================================================================
    // 6. Custom field endpoints
    // ======================================================================

    @Test
    void getCustomFieldMatches_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getCustomFieldMatches("id", "Entity Changes", null, null, null, "asc", 0, 100);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getCustomFieldMatches_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getCustomFieldMatches("nonexistent", "Entity Changes", null, null, null, "asc", 0, 100);
        assertEquals(404, response.getStatus());
    }

    @Test
    void getCustomFieldMatches_fieldNotFound_returns404() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCustomFieldMatches(analysis.getId(), "NonExistent Field", null, null, null, "asc", 0, 100);

        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCustomFieldMatches_found_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCustomFieldMatches(analysis.getId(), "Entity Changes", null, null, null, "asc", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(3, data.size());
        assertEquals(3, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCustomFieldMatches_paginatesCorrectly() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCustomFieldMatches(analysis.getId(), "Entity Changes", null, null, null, "asc", 0, 2);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(3, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCustomFieldMatches_countOnly_returnsEmptyData() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCustomFieldMatches(analysis.getId(), "Error Codes", null, null, null, "asc", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(5, entity.get("matchCount"));
        assertEquals(true, entity.get("countOnly"));
        List<?> data = (List<?>) entity.get("data");
        assertTrue(data.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void analysisSummary_includesCustomFieldsWithFieldNameKey() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getAnalysis(analysis.getId());

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<Map<String, Object>> customFields = (List<Map<String, Object>>) entity.get("customFields");
        assertEquals(2, customFields.size());
        assertEquals("Entity Changes", customFields.get(0).get("fieldName"));
        assertEquals(3, customFields.get(0).get("matchCount"));
        assertEquals("Error Codes", customFields.get(1).get("fieldName"));
        assertEquals(5, customFields.get(1).get("matchCount"));
    }

    // ======================================================================
    // 7. Upload validation
    // ======================================================================

    @Test
    void upload_noFiles_returnsBadRequest() throws Exception {
        MultipartFormDataInput input = mockUploadFormNoFiles("WILDFLY");

        Response response = controller.uploadAndAnalyze(input);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "no file");
    }

    @Test
    void upload_emptyLogLineRegex_returnsBadRequest() throws Exception {
        // Custom preset has empty logLineRegex by default, and we don't override it
        MultipartFormDataInput input = mockUploadFormWithOverrides("Custom", "");

        Response response = controller.uploadAndAnalyze(input);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "log line regex is required");
    }

    // ---- Anomaly Detection ----

    @Test
    void getAnomalyDetection_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getAnomalyDetection(ANALYSIS_ID, "ERROR_COUNT", 300, 3.0, 8, "count", "ratio");

        assertEquals(403, response.getStatus());
    }

    @Test
    void getAnomalyDetection_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getAnomalyDetection("nonexistent", "ERROR_COUNT", 300, 3.0, 8, "count", "ratio");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getAnomalyDetection_invalidSignalType_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getAnomalyDetection(analysis.getId(), "INVALID_TYPE", 300, 3.0, 8, "count", "ratio");

        assertEquals(400, response.getStatus());
    }

    @Test
    void getAnomalyDetection_nullTimeRange_returnsEmptyResponse() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                1, null, null,
                List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(new LogLine(1, now, "INFO", "app", "main", "test", "server.log"))
        );
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(signalExtractor.extract(anyList(), eq(SignalType.ERROR_COUNT), anyList(), anyList(), anyList())).thenReturn(List.of());

        Response response = controller.getAnomalyDetection(analysis.getId(), "ERROR_COUNT", 300, 3.0, 8, "count", "ratio");

        assertEquals(200, response.getStatus());
        AnomalyDetectionResponse body = (AnomalyDetectionResponse) response.getEntity();
        assertEquals("ERROR_COUNT", body.signalType());
        assertTrue(body.buckets().isEmpty());
        assertTrue(body.anomalies().isEmpty());
    }

    @Test
    void getAnomalyDetection_withTimeRange_returnsDetectionResults() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        var signals = List.of(
                new Signal(SignalType.ERROR_COUNT, null, "error msg",
                        analysis.getTimeRangeStart().plusMinutes(1), "main", "app")
        );
        when(signalExtractor.extract(anyList(), eq(SignalType.ERROR_COUNT), anyList(), anyList(), anyList())).thenReturn(signals);

        var detectionResult = new AnomalyDetectionPort.DetectionResult(
                List.of(new BucketStats("10:00", 1000, 1, 1, 1, 1, 1, "normal", 0.0, 0.0)),
                List.of()
        );
        when(anomalyDetectorService.detect(anyList(), anyDouble(), anyInt(), anyString(), anyString(), anyString())).thenReturn(detectionResult);

        Response response = controller.getAnomalyDetection(analysis.getId(), "ERROR_COUNT", 300, 3.0, 8, "count", "ratio");

        assertEquals(200, response.getStatus());
        AnomalyDetectionResponse body = (AnomalyDetectionResponse) response.getEntity();
        assertEquals("ERROR_COUNT", body.signalType());
        assertEquals(300, body.bucketSize());
        assertEquals(1, body.totalBuckets());
        assertEquals(0, body.anomalyBuckets());
    }

    @Test
    void getAnomalyDetection_apiLatencySignalType_passesApiCalls() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        when(signalExtractor.extract(anyList(), eq(SignalType.API_LATENCY), anyList(), anyList(), anyList())).thenReturn(List.of());

        var detectionResult = new AnomalyDetectionPort.DetectionResult(List.of(), List.of());
        when(anomalyDetectorService.detect(anyList(), anyDouble(), anyInt(), anyString(), anyString(), anyString())).thenReturn(detectionResult);

        Response response = controller.getAnomalyDetection(analysis.getId(), "API_LATENCY", 300, 3.0, 8, "count", "ratio");

        assertEquals(200, response.getStatus());
        verify(signalExtractor).extract(analysis.getAllLines(), SignalType.API_LATENCY, analysis.getApiCalls(), analysis.getJobExecutions(), analysis.getOrphanRequests());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAnomalyDetection_withAnomalies_triggersCorrelation() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        var signals = List.of(
                new Signal(SignalType.ERROR_COUNT, null, "error",
                        analysis.getTimeRangeStart().plusMinutes(1), "main", "app")
        );
        when(signalExtractor.extract(anyList(), eq(SignalType.ERROR_COUNT), anyList(), anyList(), anyList())).thenReturn(signals);

        var anomaly = new AnomalyResult("ERROR_COUNT", "10:00", 10.0, 2.0, 5.0, 10, 15);
        var detectionResult = new AnomalyDetectionPort.DetectionResult(
                List.of(new BucketStats("10:00", 1000, 10, 10, 1, 10, 10, "ANOMALY", 2.0, 5.0)),
                List.of(anomaly)
        );
        when(anomalyDetectorService.detect(anyList(), anyDouble(), anyInt(), anyString(), anyString(), anyString())).thenReturn(detectionResult);
        when(signalExtractor.extractAll(anyList(), anyList(), anyList(), anyList())).thenReturn(Map.of(SignalType.ERROR_COUNT, signals));

        Response response = controller.getAnomalyDetection(analysis.getId(), "ERROR_COUNT", 300, 3.0, 8, "count", "ratio");

        assertEquals(200, response.getStatus());
        AnomalyDetectionResponse body = (AnomalyDetectionResponse) response.getEntity();
        assertEquals(1, body.anomalyBuckets());
        verify(signalExtractor).extractAll(analysis.getAllLines(), analysis.getApiCalls(), analysis.getJobExecutions(), analysis.getOrphanRequests());
    }

    // ---- Signal Types ----

    @Test
    void getAvailableSignalTypes_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getAvailableSignalTypes(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getAvailableSignalTypes_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getAvailableSignalTypes("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAvailableSignalTypes_returnsStringList() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(signalExtractor.detectAvailableTypes(anyList(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(SignalType.ERROR_COUNT, SignalType.API_LATENCY));

        Response response = controller.getAvailableSignalTypes(analysis.getId());

        assertEquals(200, response.getStatus());
        List<String> types = (List<String>) response.getEntity();
        assertEquals(2, types.size());
        assertEquals("ERROR_COUNT", types.get(0));
        assertEquals("API_LATENCY", types.get(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAvailableSignalTypes_passesApiCallsToExtractor() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(signalExtractor.detectAvailableTypes(anyList(), anyList(), anyList(), anyList())).thenReturn(List.of());

        controller.getAvailableSignalTypes(analysis.getId());

        verify(signalExtractor).detectAvailableTypes(analysis.getAllLines(), analysis.getApiCalls(), analysis.getJobExecutions(), analysis.getOrphanRequests());
    }

    // ---- System Health ----

    @Test
    void getSystemHealth_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);

        Response response = controller.getSystemHealth(ANALYSIS_ID, 300, "count");

        assertEquals(403, response.getStatus());
    }

    @Test
    void getSystemHealth_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.getSystemHealth("nonexistent", 300, "count");

        assertEquals(404, response.getStatus());
    }

    @Test
    void getSystemHealth_invalidMetric_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getSystemHealth(analysis.getId(), 300, "invalid");

        assertEquals(400, response.getStatus());
    }

    @Test
    void getSystemHealth_nullTimeRange_returnsEmpty() {
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                1, null, null,
                List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(new LogLine(1, LocalDateTime.of(2025, 6, 15, 10, 0, 0), "INFO", "app", "main", "test", "server.log"))
        );
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getSystemHealth(analysis.getId(), 300, "count");

        assertEquals(200, response.getStatus());
        SystemHealthResponse body = (SystemHealthResponse) response.getEntity();
        assertTrue(body.signalTypes().isEmpty());
        assertTrue(body.buckets().isEmpty());
    }

    @Test
    void getSystemHealth_withData_returnsBuckets() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        var signals = Map.of(
                SignalType.ERROR_COUNT, List.of(
                        new Signal(SignalType.ERROR_COUNT, null, "error",
                                analysis.getTimeRangeStart().plusMinutes(1), "main", "app")),
                SignalType.API_LATENCY, List.of(
                        new Signal(SignalType.API_LATENCY, 500L, "endpoint 500ms",
                                analysis.getTimeRangeStart().plusMinutes(1), "main", "app"))
        );
        when(signalExtractor.extractAll(anyList(), anyList(), anyList(), anyList())).thenReturn(signals);

        Response response = controller.getSystemHealth(analysis.getId(), 300, "count");

        assertEquals(200, response.getStatus());
        SystemHealthResponse body = (SystemHealthResponse) response.getEntity();
        assertEquals(300, body.bucketSize());
        assertEquals("count", body.metric());
        assertFalse(body.signalTypes().isEmpty());
        assertFalse(body.buckets().isEmpty());
    }

    @Test
    void getSystemHealth_includesDurationSignalsInResponse() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        var signals = Map.of(
                SignalType.ERROR_COUNT, List.of(
                        new Signal(SignalType.ERROR_COUNT, null, "error",
                                analysis.getTimeRangeStart().plusMinutes(1), "main", "app")),
                SignalType.API_LATENCY, List.of(
                        new Signal(SignalType.API_LATENCY, 500L, "ep 500ms",
                                analysis.getTimeRangeStart().plusMinutes(1), "main", "app"))
        );
        when(signalExtractor.extractAll(anyList(), anyList(), anyList(), anyList())).thenReturn(signals);

        Response response = controller.getSystemHealth(analysis.getId(), 300, "avg");

        SystemHealthResponse body = (SystemHealthResponse) response.getEntity();
        assertTrue(body.durationSignals().contains("API_LATENCY"));
        assertFalse(body.durationSignals().contains("ERROR_COUNT"));
    }

    @Test
    void getSystemHealth_includesJobDurationAsDurationSignal() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        var signals = Map.of(
                SignalType.JOB_DURATION, List.of(
                        new Signal(SignalType.JOB_DURATION, 10000L, "CleanupJob 10000ms",
                                analysis.getTimeRangeStart().plusMinutes(1), "sched-1", "CleanupJob")),
                SignalType.ERROR_COUNT, List.of(
                        new Signal(SignalType.ERROR_COUNT, null, "error",
                                analysis.getTimeRangeStart().plusMinutes(1), "main", "app"))
        );
        when(signalExtractor.extractAll(anyList(), anyList(), anyList(), anyList())).thenReturn(signals);

        Response response = controller.getSystemHealth(analysis.getId(), 300, "avg");

        assertEquals(200, response.getStatus());
        SystemHealthResponse body = (SystemHealthResponse) response.getEntity();
        assertTrue(body.durationSignals().contains("JOB_DURATION"));
        assertTrue(body.signalTypes().contains("JOB_DURATION"));
        assertFalse(body.buckets().isEmpty());
    }

    // ── Report Download Tests ──────────────────────────────────────────────

    @Test
    void downloadReport_compact_returns200WithHtml() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.downloadReport(analysis.getId(), "compact");

        assertEquals(200, response.getStatus());
        String html = (String) response.getEntity();
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Compact"));
        assertNotNull(response.getHeaderString("Content-Disposition"));
    }

    @Test
    void downloadReport_complete_returns200WithHtml() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.downloadReport(analysis.getId(), "complete");

        assertEquals(200, response.getStatus());
        String html = (String) response.getEntity();
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Complete"));
    }

    @Test
    void downloadReport_invalidType_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.downloadReport(analysis.getId(), "invalid");

        assertEquals(400, response.getStatus());
    }

    @Test
    void downloadReport_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.downloadReport("nonexistent", "compact");

        assertEquals(404, response.getStatus());
    }

    @Test
    void downloadReport_disabled_returns403() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.downloadReport("id", "compact");
        assertEquals(403, response.getStatus());
    }

    @Test
    void downloadReport_withLabel_usesLabelInFilename() {
        LogAnalysis analysis = buildSampleAnalysis();
        analysis.setLabel("my-server");
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.downloadReport(analysis.getId(), "compact");

        assertEquals(200, response.getStatus());
        String disposition = response.getHeaderString("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("my-server"));
    }

    // ── Stats Export Tests ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void exportApiStats_returns200WithMetadata() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.exportApiStats(analysis.getId());

        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(1, body.get("version"));
        assertNotNull(body.get("label"));
        assertNotNull(body.get("exportedAt"));
        assertNotNull(body.get("endpoints"));
        List<?> endpoints = (List<?>) body.get("endpoints");
        assertEquals(2, endpoints.size());
        assertNotNull(response.getHeaderString("Content-Disposition"));
    }

    @Test
    void exportApiStats_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);

        Response response = controller.exportApiStats("nonexistent");

        assertEquals(404, response.getStatus());
    }

    @Test
    void exportApiStats_disabled_returns403() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.exportApiStats("id");
        assertEquals(403, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportApiStats_withLabel_usesLabel() {
        LogAnalysis analysis = buildSampleAnalysis();
        analysis.setLabel("production-server");
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.exportApiStats(analysis.getId());

        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("production-server", body.get("label"));
    }

    // ── Stats Comparison Tests ─────────────────────────────────────────────

    @Test
    void compareStats_validData_returns200WithHtml() {
        var body = new HashMap<String, Object>();
        body.put("labelA", "Server A");
        body.put("labelB", "Server B");
        body.put("endpointsA", List.of(
                Map.of("endpoint", "UserWS/get", "callCount", 100, "avgDurationMs", 200.0, "minDurationMs", 50, "maxDurationMs", 500, "p95DurationMs", 400, "slowCount", 5)
        ));
        body.put("endpointsB", List.of(
                Map.of("endpoint", "UserWS/get", "callCount", 150, "avgDurationMs", 100.0, "minDurationMs", 30, "maxDurationMs", 300, "p95DurationMs", 250, "slowCount", 2)
        ));

        Response response = controller.compareStats(body);

        assertEquals(200, response.getStatus());
        String html = (String) response.getEntity();
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Server A"));
        assertTrue(html.contains("Server B"));
        assertTrue(html.contains("UserWS/get"));
        assertNotNull(response.getHeaderString("Content-Disposition"));
    }

    @Test
    void compareStats_missingEndpoints_returns400() {
        var body = new HashMap<String, Object>();
        body.put("labelA", "A");

        Response response = controller.compareStats(body);

        assertEquals(400, response.getStatus());
    }

    @Test
    void compareStats_disabled_returns403() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.compareStats(Map.of());
        assertEquals(403, response.getStatus());
    }

    @Test
    void compareStats_emptyEndpoints_returns200() {
        var body = new HashMap<String, Object>();
        body.put("labelA", "A");
        body.put("labelB", "B");
        body.put("endpointsA", List.of());
        body.put("endpointsB", List.of());

        Response response = controller.compareStats(body);

        assertEquals(200, response.getStatus());
        String html = (String) response.getEntity();
        assertTrue(html.contains("<!DOCTYPE html>"));
    }

    // ======================================================================
    // Characterization tests — Performance Insights
    // ======================================================================

    @Test
    void getPerformanceInsights_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getPerformanceInsights(ANALYSIS_ID, null);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getPerformanceInsights_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getPerformanceInsights("nonexistent", null);
        assertEquals(404, response.getStatus());
    }

    @Test
    void getPerformanceInsights_noApiCalls_returnsEmptyInsights() {
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                1, LocalDateTime.of(2025, 6, 15, 10, 0, 0), LocalDateTime.of(2025, 6, 15, 10, 30, 0),
                List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getPerformanceInsights(analysis.getId(), null);

        assertEquals(200, response.getStatus());
        PerformanceInsights insights = (PerformanceInsights) response.getEntity();
        assertTrue(insights.timeBuckets().isEmpty());
        assertTrue(insights.topEndpointsByImpact().isEmpty());
    }

    @Test
    void getPerformanceInsights_withApiCalls_returnsInsights() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getPerformanceInsights(analysis.getId(), null);

        assertEquals(200, response.getStatus());
        PerformanceInsights insights = (PerformanceInsights) response.getEntity();
        assertNotNull(insights);
        assertNotNull(insights.bucketWidth());
    }

    @Test
    void getPerformanceInsights_filteredByEndpoint_usesOnlyMatchingCalls() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getPerformanceInsights(analysis.getId(), "UserResource/getUser");

        assertEquals(200, response.getStatus());
        PerformanceInsights insights = (PerformanceInsights) response.getEntity();
        assertNotNull(insights);
    }

    @Test
    void getPerformanceInsights_blankEndpoint_treatedAsNoFilter() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response unfiltered = controller.getPerformanceInsights(analysis.getId(), null);
        Response blankFilter = controller.getPerformanceInsights(analysis.getId(), "  ");

        // Both should use all calls
        assertEquals(200, unfiltered.getStatus());
        assertEquals(200, blankFilter.getStatus());
    }

    // ======================================================================
    // Characterization tests — Bucket Endpoints
    // ======================================================================

    @Test
    void getBucketEndpoints_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getBucketEndpoints(ANALYSIS_ID, "2025-06-15T10:00:00", null, 7);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getBucketEndpoints_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getBucketEndpoints("nonexistent", "2025-06-15T10:00:00", null, 7);
        assertEquals(404, response.getStatus());
    }

    @Test
    void getBucketEndpoints_missingTimestamp_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getBucketEndpoints(analysis.getId(), null, null, 7);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "timestamp");
    }

    @Test
    void getBucketEndpoints_blankTimestamp_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getBucketEndpoints(analysis.getId(), "  ", null, 7);

        assertEquals(400, response.getStatus());
    }

    @Test
    void getBucketEndpoints_invalidTimestamp_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getBucketEndpoints(analysis.getId(), "not-a-date", null, 7);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "invalid timestamp");
    }

    @Test
    void getBucketEndpoints_validTimestamp_returns200() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getBucketEndpoints(analysis.getId(),
                analysis.getTimeRangeStart().toString(), null, 7);

        assertEquals(200, response.getStatus());
    }

    @Test
    void getBucketEndpoints_limitClampedTo1Minimum() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // Limit of 0 should be clamped to 1
        Response response = controller.getBucketEndpoints(analysis.getId(),
                analysis.getTimeRangeStart().toString(), null, 0);

        assertEquals(200, response.getStatus());
    }

    @Test
    void getBucketEndpoints_filteredByEndpoint() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getBucketEndpoints(analysis.getId(),
                analysis.getTimeRangeStart().toString(), "UserResource/getUser", 7);

        assertEquals(200, response.getStatus());
    }

    // ======================================================================
    // Characterization tests — Line Range
    // ======================================================================

    @Test
    void getLineRange_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getLineRange(ANALYSIS_ID, 1, 10, null, 0, 500);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getLineRange_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getLineRange("nonexistent", 1, 10, null, 0, 500);
        assertEquals(404, response.getStatus());
    }

    @Test
    void getLineRange_invalidRange_fromZero_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLineRange(analysis.getId(), 0, 10, null, 0, 500);

        assertEquals(400, response.getStatus());
        assertErrorContains(response, "invalid range");
    }

    @Test
    void getLineRange_invalidRange_fromGreaterThanTo_returns400() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLineRange(analysis.getId(), 10, 5, null, 0, 500);

        assertEquals(400, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLineRange_validRange_returns200WithPagination() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLineRange(analysis.getId(), 1, 4, null, 0, 500);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(4, data.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLineRange_filteredByLevel_returnsOnlyMatchingLevel() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLineRange(analysis.getId(), 1, 4, "ERROR", 0, 500);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("ERROR", data.getFirst().level());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLineRange_sizeClampedTo1000Max() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLineRange(analysis.getId(), 1, 4, null, 0, 5000);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(1000, entity.get("size"));
    }

    // ======================================================================
    // Characterization tests — Critical Issues
    // ======================================================================

    private LogAnalysis buildAnalysisWithCriticalIssues() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                4, now, now.plusSeconds(60),
                List.of("main"), List.of(),
                List.of(), List.of(), Map.of("ERROR", 4), List.of(),
                List.of(), List.of(), List.of()
        );

        var issues1 = List.of(
                new CriticalIssue("JDBC_CONNECT_ERROR", "HIGH", "Cannot get JDBC connection",
                        10, now, "Connection refused", "server.log"),
                new CriticalIssue("JDBC_CONNECT_ERROR", "HIGH", "Cannot get JDBC connection",
                        20, now.plusSeconds(5), "Connection refused", "server.log")
        );
        var issues2 = List.of(
                new CriticalIssue("OUT_OF_MEMORY", "CRITICAL", "OutOfMemoryError",
                        30, now.plusSeconds(10), "Java heap space", "server.log")
        );

        analysis.setCriticalIssues(List.of(
                new CriticalIssueSummary("JDBC_CONNECT_ERROR", "HIGH", 2, now, now.plusSeconds(5), issues1, List.of()),
                new CriticalIssueSummary("OUT_OF_MEMORY", "CRITICAL", 1, now.plusSeconds(10), now.plusSeconds(10), issues2, List.of())
        ));
        return analysis;
    }

    @Test
    void getCriticalIssues_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getCriticalIssues(ANALYSIS_ID, null);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getCriticalIssues_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getCriticalIssues("nonexistent", null);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalIssues_noFilter_returnsAll() {
        LogAnalysis analysis = buildAnalysisWithCriticalIssues();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCriticalIssues(analysis.getId(), null);

        assertEquals(200, response.getStatus());
        List<CriticalIssueSummaryResponse> summaries = (List<CriticalIssueSummaryResponse>) response.getEntity();
        assertEquals(2, summaries.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalIssues_filterByCategory_returnsOnlyMatching() {
        LogAnalysis analysis = buildAnalysisWithCriticalIssues();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCriticalIssues(analysis.getId(), "OUT_OF_MEMORY");

        assertEquals(200, response.getStatus());
        List<CriticalIssueSummaryResponse> summaries = (List<CriticalIssueSummaryResponse>) response.getEntity();
        assertEquals(1, summaries.size());
        assertEquals("OUT_OF_MEMORY", summaries.getFirst().category());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalIssues_filterCaseInsensitive() {
        LogAnalysis analysis = buildAnalysisWithCriticalIssues();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCriticalIssues(analysis.getId(), "out_of_memory");

        assertEquals(200, response.getStatus());
        List<CriticalIssueSummaryResponse> summaries = (List<CriticalIssueSummaryResponse>) response.getEntity();
        assertEquals(1, summaries.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalIssues_blankCategory_returnsAll() {
        LogAnalysis analysis = buildAnalysisWithCriticalIssues();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getCriticalIssues(analysis.getId(), "  ");

        assertEquals(200, response.getStatus());
        List<CriticalIssueSummaryResponse> summaries = (List<CriticalIssueSummaryResponse>) response.getEntity();
        assertEquals(2, summaries.size());
    }

    // ======================================================================
    // Characterization tests — Critical Bursts
    // ======================================================================

    private LogAnalysis buildAnalysisWithBursts() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                10, now, now.plusMinutes(30),
                List.of("main"), List.of(),
                List.of(), List.of(), Map.of("ERROR", 10), List.of(),
                List.of(), List.of(), List.of()
        );

        var issues = new java.util.ArrayList<CriticalIssue>();
        for (int i = 0; i < 5; i++) {
            issues.add(new CriticalIssue("JDBC_CONNECT_ERROR", "HIGH", "Connection refused",
                    10 + i, now.plusMinutes(i), "Connection refused to db-host", "server.log"));
        }
        var burst = new CriticalBurst("JDBC_CONNECT_ERROR", "HIGH", now, now.plusMinutes(4), 5, issues);

        analysis.setCriticalIssues(List.of(
                new CriticalIssueSummary("JDBC_CONNECT_ERROR", "HIGH", 5,
                        now, now.plusMinutes(4), issues, List.of(burst))
        ));
        return analysis;
    }

    @Test
    void getCriticalBursts_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getCriticalBursts(ANALYSIS_ID, null, null);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getCriticalBursts_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getCriticalBursts("nonexistent", null, null);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBursts_defaultParams_usesCache() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // No cached bursts → computeBursts should be called
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBursts(analysis.getId(), null, null);

        assertEquals(200, response.getStatus());
        List<Map<String, Object>> result = (List<Map<String, Object>>) response.getEntity();
        assertEquals(1, result.size());
        assertEquals("JDBC_CONNECT_ERROR", result.getFirst().get("category"));
        assertEquals(1, result.getFirst().get("burstCount"));
        assertEquals(5, result.getFirst().get("totalBurstIssues"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBursts_customParams_bypassesCache() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        when(criticalIssueDetector.computeBursts(anyList(), anyInt(), anyInt())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBursts(analysis.getId(), 20, 10);

        assertEquals(200, response.getStatus());
        // Should call the parameterized version, not the default
        verify(criticalIssueDetector).computeBursts(anyList(), eq(20), eq(10));
        verify(criticalIssueDetector, never()).computeBursts(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBursts_thresholdClamped() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList(), anyInt(), anyInt())).thenReturn(analysis.getCriticalIssues());

        // Threshold 0 should be clamped to 2
        controller.getCriticalBursts(analysis.getId(), 0, null);

        verify(criticalIssueDetector).computeBursts(anyList(), eq(2), eq(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBursts_noBursts_returnsEmptyList() {
        LogAnalysis analysis = buildAnalysisWithCriticalIssues(); // has issues but no bursts
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBursts(analysis.getId(), null, null);

        assertEquals(200, response.getStatus());
        List<?> result = (List<?>) response.getEntity();
        assertTrue(result.isEmpty());
    }

    // ======================================================================
    // Characterization tests — Critical Bursts By Category
    // ======================================================================

    @Test
    void getCriticalBurstsByCategory_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getCriticalBurstsByCategory(ANALYSIS_ID, "JDBC_CONNECT_ERROR", 0, 10);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getCriticalBurstsByCategory_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getCriticalBurstsByCategory("nonexistent", "JDBC_CONNECT_ERROR", 0, 10);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBurstsByCategory_categoryNotFound_returnsEmptyPaginated() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstsByCategory(analysis.getId(), "NONEXISTENT", 0, 10);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertTrue(data.isEmpty());
        assertEquals(0, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBurstsByCategory_validCategory_returnsPaginated() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstsByCategory(analysis.getId(), "JDBC_CONNECT_ERROR", 0, 10);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals(1, entity.get("total"));
    }

    // ======================================================================
    // Characterization tests — Critical Burst Issues
    // ======================================================================

    @Test
    void getCriticalBurstIssues_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getCriticalBurstIssues(ANALYSIS_ID, "JDBC_CONNECT_ERROR", 0, 0, 25);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getCriticalBurstIssues_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getCriticalBurstIssues("nonexistent", "JDBC_CONNECT_ERROR", 0, 0, 25);
        assertEquals(404, response.getStatus());
    }

    @Test
    void getCriticalBurstIssues_categoryNotFound_returns404() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstIssues(analysis.getId(), "NONEXISTENT", 0, 0, 25);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getCriticalBurstIssues_invalidBurstIndex_returns404() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstIssues(analysis.getId(), "JDBC_CONNECT_ERROR", 99, 0, 25);

        assertEquals(404, response.getStatus());
    }

    @Test
    void getCriticalBurstIssues_negativeBurstIndex_returns404() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstIssues(analysis.getId(), "JDBC_CONNECT_ERROR", -1, 0, 25);

        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBurstIssues_validBurst_returnsPaginatedIssues() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstIssues(analysis.getId(), "JDBC_CONNECT_ERROR", 0, 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(5, data.size());
        assertEquals(5, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCriticalBurstIssues_pagination_returnsCorrectPage() {
        LogAnalysis analysis = buildAnalysisWithBursts();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);
        when(criticalIssueDetector.computeBursts(anyList())).thenReturn(analysis.getCriticalIssues());

        Response response = controller.getCriticalBurstIssues(analysis.getId(), "JDBC_CONNECT_ERROR", 0, 0, 2);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(5, entity.get("total"));
    }

    // ======================================================================
    // Characterization tests — NPE Analysis
    // ======================================================================

    private LogAnalysis buildAnalysisWithNpeData() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                4, now, now.plusSeconds(60),
                List.of("main"), List.of(),
                List.of(), List.of(), Map.of("ERROR", 4), List.of(),
                List.of(), List.of(), List.of()
        );

        var occurrences = List.of(
                new NpeOccurrence("com.example.Service", "process", "Service.java", 42,
                        "null at process", now, 10, "server.log", List.of("at com.example.Service.process(Service.java:42)")),
                new NpeOccurrence("com.example.Service", "process", "Service.java", 42,
                        "null at process", now.plusSeconds(30), 50, "server.log", List.of("at com.example.Service.process(Service.java:42)"))
        );

        analysis.setNpeAnalysis(List.of(
                new NpeLocationSummary("com.example.Service.process(Service.java:42)",
                        "com.example.Service", "process", "Service.java", 42,
                        2, now, now.plusSeconds(30), occurrences)
        ));
        return analysis;
    }

    @Test
    void getNpeAnalysis_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getNpeAnalysis(ANALYSIS_ID, 0, 50);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getNpeAnalysis_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getNpeAnalysis("nonexistent", 0, 50);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getNpeAnalysis_returnsSummariesWithOccurrencesStripped() {
        LogAnalysis analysis = buildAnalysisWithNpeData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getNpeAnalysis(analysis.getId(), 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<NpeLocationSummaryResponse> data = (List<NpeLocationSummaryResponse>) entity.get("data");
        assertEquals(1, data.size());
        NpeLocationSummaryResponse summary = data.getFirst();
        assertEquals("com.example.Service.process(Service.java:42)", summary.origin());
        assertEquals(2, summary.count());
        assertTrue(summary.occurrences().isEmpty(), "Occurrences should be stripped in summary");
    }

    // ======================================================================
    // Characterization tests — NPE Occurrences
    // ======================================================================

    @Test
    void getNpeOccurrences_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getNpeOccurrences(ANALYSIS_ID, "some.origin", 0, 25);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getNpeOccurrences_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getNpeOccurrences("nonexistent", "some.origin", 0, 25);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getNpeOccurrences_originNotFound_returnsEmptyPaginated() {
        LogAnalysis analysis = buildAnalysisWithNpeData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getNpeOccurrences(analysis.getId(), "nonexistent.origin", 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertTrue(data.isEmpty());
        assertEquals(0, entity.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getNpeOccurrences_validOrigin_returnsPaginatedOccurrences() {
        LogAnalysis analysis = buildAnalysisWithNpeData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getNpeOccurrences(analysis.getId(),
                "com.example.Service.process(Service.java:42)", 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(2, entity.get("total"));
    }

    // ======================================================================
    // Characterization tests — Exception Analysis
    // ======================================================================

    private LogAnalysis buildAnalysisWithExceptionData() {
        LocalDateTime now = LocalDateTime.of(2025, 6, 15, 10, 0, 0);
        var analysis = new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("server.log", 1024)),
                4, now, now.plusSeconds(60),
                List.of("main"), List.of(),
                List.of(), List.of(), Map.of("ERROR", 4), List.of(),
                List.of(), List.of(), List.of()
        );

        var occurrences = List.of(
                new ExceptionOccurrence("IllegalStateException", "com.example.Handler", "handle",
                        "Handler.java", 55, "Invalid state",
                        now, 15, "server.log", List.of("at com.example.Handler.handle(Handler.java:55)")),
                new ExceptionOccurrence("IllegalStateException", "com.example.Handler", "handle",
                        "Handler.java", 55, "Invalid state",
                        now.plusSeconds(20), 35, "server.log", List.of("at com.example.Handler.handle(Handler.java:55)"))
        );

        analysis.setExceptionAnalysis(List.of(
                new ExceptionLocationSummary("IllegalStateException",
                        "com.example.Handler.handle(Handler.java:55)",
                        "com.example.Handler", "handle", "Handler.java", 55,
                        2, now, now.plusSeconds(20), occurrences)
        ));
        return analysis;
    }

    @Test
    void getExceptionAnalysis_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getExceptionAnalysis(ANALYSIS_ID, 0, 50);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getExceptionAnalysis_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getExceptionAnalysis("nonexistent", 0, 50);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExceptionAnalysis_returnsSummariesWithOccurrencesStripped() {
        LogAnalysis analysis = buildAnalysisWithExceptionData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getExceptionAnalysis(analysis.getId(), 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ExceptionLocationSummaryResponse> data = (List<ExceptionLocationSummaryResponse>) entity.get("data");
        assertEquals(1, data.size());
        ExceptionLocationSummaryResponse summary = data.getFirst();
        assertEquals("IllegalStateException", summary.exceptionType());
        assertEquals(2, summary.count());
        assertTrue(summary.occurrences().isEmpty(), "Occurrences should be stripped in summary");
    }

    // ======================================================================
    // Characterization tests — Exception Occurrences
    // ======================================================================

    @Test
    void getExceptionOccurrences_disabled_returnsForbidden() {
        when(runtimeSettings.isLogAnalyzerEnabled()).thenReturn(false);
        Response response = controller.getExceptionOccurrences(ANALYSIS_ID, "some.origin", 0, 25);
        assertEquals(403, response.getStatus());
    }

    @Test
    void getExceptionOccurrences_notFound_returns404() {
        when(analyzeLogFileUseCase.get("nonexistent")).thenReturn(null);
        Response response = controller.getExceptionOccurrences("nonexistent", "some.origin", 0, 25);
        assertEquals(404, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExceptionOccurrences_originNotFound_returnsEmptyPaginated() {
        LogAnalysis analysis = buildAnalysisWithExceptionData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getExceptionOccurrences(analysis.getId(), "nonexistent.origin", 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertTrue(data.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExceptionOccurrences_matchByOrigin_returnsPaginated() {
        LogAnalysis analysis = buildAnalysisWithExceptionData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getExceptionOccurrences(analysis.getId(),
                "com.example.Handler.handle(Handler.java:55)", 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExceptionOccurrences_matchByCompositeKey_returnsPaginated() {
        LogAnalysis analysis = buildAnalysisWithExceptionData();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // The controller matches on either origin or exceptionType:origin composite key
        Response response = controller.getExceptionOccurrences(analysis.getId(),
                "IllegalStateException:com.example.Handler.handle(Handler.java:55)", 0, 25);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
    }

    // ======================================================================
    // Characterization tests — Lines filtering (additional coverage)
    // ======================================================================

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByThread_returnsOnlyMatchingThread() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), "http-thread-1", null, null, null, 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(2, data.size());
        assertTrue(data.stream().allMatch(l -> "http-thread-1".equals(l.thread())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByLevel_errorIncludesFatalAndSevere() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, "ERROR", null, null, 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("ERROR", data.getFirst().level());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredBySearch_caseInsensitive() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, "SLOW QUERY", null, 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertTrue(data.getFirst().message().contains("Slow query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByExclude_excludesMatchingLines() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, "Slow query", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertTrue(data.stream().noneMatch(l -> l.message() != null && l.message().toLowerCase().contains("slow query")));
        assertEquals(3, data.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByExclude_multiplePatterns_excludesAll() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, "Slow query,Started", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertTrue(data.stream().noneMatch(l -> l.message() != null &&
                (l.message().toLowerCase().contains("slow query") || l.message().toLowerCase().contains("started"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByExclude_caseInsensitive() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, "SLOW QUERY", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(3, data.size());
        assertTrue(data.stream().noneMatch(l -> l.message() != null && l.message().toLowerCase().contains("slow query")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByExclude_blankExclude_returnsAll() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, "  ", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertEquals(4, data.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredBySearchAndExclude_bothApply() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        // Search for "e" matches all 4 lines; exclude "Slow query" removes 1
        Response response = controller.getLines(analysis.getId(), null, null, "e", "Slow query", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertTrue(data.stream().allMatch(l -> l.message().toLowerCase().contains("e")));
        assertTrue(data.stream().noneMatch(l -> l.message().toLowerCase().contains("slow query")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getLines_filteredByExclude_patternsWithWhitespace_trimmed() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getLines(analysis.getId(), null, null, null, " Slow query , started ", 0, 100);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<LogLineResponse> data = (List<LogLineResponse>) entity.get("data");
        assertTrue(data.stream().noneMatch(l -> l.message() != null &&
                (l.message().toLowerCase().contains("slow query") || l.message().toLowerCase().contains("started"))));
    }

    // ======================================================================
    // Characterization tests — API Calls sorting
    // ======================================================================

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_sortByDuration_ascending() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, null, null, null, "duration", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(2, data.size());
        assertTrue(data.get(0).durationMs() <= data.get(1).durationMs());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_sortByDuration_descending() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, null, null, null, "duration", "desc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(2, data.size());
        assertTrue(data.get(0).durationMs() >= data.get(1).durationMs());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_sortByEndpoint() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, null, null, null, "endpoint", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(2, data.size());
        assertTrue(data.get(0).endpoint().compareTo(data.get(1).endpoint()) <= 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_filterByMinDuration() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, 1000L, null, false, false, null, null, null, null, null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("OrderResource/create", data.getFirst().endpoint());
    }

    // ======================================================================
    // Characterization tests — API Calls exclude filter
    // ======================================================================

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_filteredByExclude_excludesMatchingEndpoint() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, "UserResource", null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("OrderResource/create", data.getFirst().endpoint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_filteredByExclude_multiplePatterns() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, "UserResource,OrderResource", null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(0, data.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getApiCalls_filteredByExclude_excludesMatchingPayload() {
        LogAnalysis analysis = buildSampleAnalysis();
        when(analyzeLogFileUseCase.get(analysis.getId())).thenReturn(analysis);

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, null, false, false, null, null, null, "item", null, null, "time", "asc", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<ApiCallPairResponse> data = (List<ApiCallPairResponse>) entity.get("data");
        assertEquals(1, data.size());
        assertEquals("UserResource/getUser", data.getFirst().endpoint());
    }
}
