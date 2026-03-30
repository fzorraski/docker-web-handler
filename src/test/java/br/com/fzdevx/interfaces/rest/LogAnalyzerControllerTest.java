package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.AnalyzeLogFileUseCase;
import br.com.fzdevx.domain.model.*;
import br.com.fzdevx.infrastructure.config.LogPresetProvider;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
    LogPresetProvider logPresetProvider;

    @InjectMocks
    LogAnalyzerController controller;

    @BeforeEach
    void setUp() {
        setField("enabled", true);
        setField("maxFileSizeMb", 500);
        setField("defaultSlowThresholdMs", 1000);
        setField("defaultPresetName", "WILDFLY");
        when(logPresetProvider.allPresets()).thenReturn(LogPreset.allPresets());
        when(logPresetProvider.byName(anyString())).thenAnswer(inv -> LogPreset.byName(inv.getArgument(0)));
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
                        now, now.plusSeconds(1), 150, "{}", "{\"id\":1}", 1, 2, "server.log", false),
                new ApiCallPair("OrderResource/create", "102", "http-thread-2",
                        now.plusSeconds(2), now.plusSeconds(5), 3000, "{\"item\":1}", "{\"id\":2}", 3, 4, "server.log", true)
        );

        var endpointStats = List.of(
                new EndpointStats("UserResource/getUser", 1, 150.0, 150, 150, 150, 0),
                new EndpointStats("OrderResource/create", 1, 3000.0, 3000, 3000, 3000, 1)
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

        return new LogAnalysis(sourceFiles, lines.size(), now, now.plusSeconds(30),
                threads, endpoints, apiCalls, endpointStats, levelCounts, errors,
                jobExecutions, repeatedFailures, lines);
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
        setField("enabled", false);

        Response response = controller.uploadAndAnalyze(mock(MultipartFormDataInput.class));

        assertEquals(403, response.getStatus());
    }

    @Test
    void getAnalysis_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getAnalysis(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteAnalysis_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.deleteAnalysis(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getApiCalls_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getApiCalls(ANALYSIS_ID, null, null, null, "time", 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getApiStats_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getApiStats(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getLines_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getLines(ANALYSIS_ID, null, null, null, 0, 100);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getThreads_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getThreads(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getEndpoints_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getEndpoints(ANALYSIS_ID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getJobs_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getJobs(ANALYSIS_ID, 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void getFailures_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.getFailures(ANALYSIS_ID, 0, 50);

        assertEquals(403, response.getStatus());
    }

    @Test
    void listAnalyses_disabled_returnsForbidden() {
        setField("enabled", false);

        Response response = controller.listAnalyses();

        assertEquals(403, response.getStatus());
    }

    @Test
    void compose_disabled_returnsForbidden() {
        setField("enabled", false);

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

        Response response = controller.getApiCalls("nonexistent", null, null, null, "time", 0, 50);

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

        Response response = controller.getLines("nonexistent", null, null, null, 0, 100);

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

        Response response = controller.getJobs("nonexistent", 0, 50);

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

        Response response = controller.getApiCalls(analysis.getId(), null, null, null, "time", 0, 50);

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        List<?> data = (List<?>) entity.get("data");
        assertEquals(2, data.size());
        assertEquals(2, entity.get("total"));
        assertEquals(0, entity.get("page"));
        assertEquals(50, entity.get("size"));
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

        Response response = controller.getLines(analysis.getId(), null, null, null, 0, 100);

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

        Response response = controller.getJobs(analysis.getId(), 0, 50);

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
        when(analyzeLogFileUseCase.compose(anyList(), any(LogPreset.class), anyInt())).thenReturn(composed);

        Response response = controller.compose(Map.of("ids", List.of("id1", "id2")));

        assertEquals(200, response.getStatus());
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(composed.getId(), entity.get("id"));
        assertEquals(4, entity.get("totalLineCount"));
    }

    // ======================================================================
    // 5. Upload validation
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
}
