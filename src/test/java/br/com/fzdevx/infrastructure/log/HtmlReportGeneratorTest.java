package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportGeneratorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 15, 10, 0, 0);

    private LogAnalysis buildAnalysis() {
        var sourceFiles = List.of(new LogAnalysis.SourceFile("server.log", 1024));
        var apiCalls = List.of(
                new ApiCallPair("UserWS/get", "c1", "thread-1", NOW, NOW.plusSeconds(1), 150, "{}", "{}", 1, 2, "server.log", false),
                new ApiCallPair("OrderWS/create", "c2", "thread-2", NOW.plusSeconds(2), NOW.plusSeconds(5), 3000, "{}", "{}", 3, 4, "server.log", true)
        );
        var stats = List.of(
                new EndpointStats("UserWS/get", 50, 150.0, 50, 300, 250, 0),
                new EndpointStats("OrderWS/create", 20, 3000.0, 1000, 5000, 4500, 5)
        );
        var jobs = List.of(
                new JobExecution("CleanupJob", "trigger", "sched-1", NOW, NOW.plusSeconds(10), 10000, "SUCCESS", 10, 20, "server.log")
        );
        var failures = List.of(
                new RepeatedFailure("ORDER 1", "TIMEOUT", 3, NOW, NOW.plusSeconds(30),
                        List.of(new RepeatedFailure.FailureDetail(NOW, 5, "Timeout", "server.log")))
        );
        var lines = List.of(
                new LogLine(1, NOW, "INFO", "App", "main", "Started", "server.log"),
                new LogLine(2, NOW.plusSeconds(1), "ERROR", "Api", "thread-1", "NPE", "server.log")
        );
        var analysis = new LogAnalysis(sourceFiles, 2, NOW, NOW.plusSeconds(30),
                List.of("main", "thread-1", "thread-2"), List.of("UserWS/get", "OrderWS/create"),
                apiCalls, stats, Map.of("INFO", 1, "ERROR", 1), List.of(lines.get(1)),
                jobs, failures, lines);
        analysis.setCriticalIssues(List.of(
                new CriticalIssueSummary("JDBC", "CRITICAL", 2, NOW, NOW.plusSeconds(5),
                        List.of(new CriticalIssue("JDBC", "CRITICAL", "conn fail", 10, NOW, "Connection refused", "server.log")),
                        List.of())
        ));
        analysis.setExceptionAnalysis(List.of(
                new ExceptionLocationSummary("NullPointerException", "App.main(App.java:10)", "App", "main", "App.java", 10, 3, NOW, NOW.plusSeconds(20), List.of())
        ));
        analysis.setCustomFieldResults(List.of(
                new CustomFieldResult("Entity Changes", 5, false, List.of())
        ));
        return analysis;
    }

    @Test
    void generateCompact_containsAllSections() {
        var analysis = buildAnalysis();
        String html = HtmlReportGenerator.generateCompact(analysis);

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("server.log"));
        // Summary
        assertTrue(html.contains("Total Lines"));
        assertTrue(html.contains("API Calls"));
        // Level distribution
        assertTrue(html.contains("INFO"));
        assertTrue(html.contains("ERROR"));
        // Top endpoints
        assertTrue(html.contains("UserWS/get"));
        assertTrue(html.contains("OrderWS/create"));
        // Critical issues
        assertTrue(html.contains("JDBC"));
        assertTrue(html.contains("CRITICAL"));
        // Exception summary
        assertTrue(html.contains("NullPointerException"));
        // Job summary
        assertTrue(html.contains("CleanupJob"));
        // Footer
        assertTrue(html.contains("Docker Web Handler"));
    }

    @Test
    void generateComplete_containsAdditionalSections() {
        var analysis = buildAnalysis();
        String html = HtmlReportGenerator.generateComplete(analysis);

        assertTrue(html.contains("<!DOCTYPE html>"));
        // All compact sections
        assertTrue(html.contains("UserWS/get"));
        assertTrue(html.contains("JDBC"));
        // Complete-only sections
        assertTrue(html.contains("Repeated Failures"));
        assertTrue(html.contains("ORDER 1"));
        assertTrue(html.contains("TIMEOUT"));
        // Top slowest calls
        assertTrue(html.contains("Top 100"));
        assertTrue(html.contains("OrderWS/create"));
        // Custom fields
        assertTrue(html.contains("Entity Changes"));
    }

    @Test
    void generateCompact_withLabel_usesLabel() {
        var analysis = buildAnalysis();
        analysis.setLabel("production-server");
        String html = HtmlReportGenerator.generateCompact(analysis);

        assertTrue(html.contains("production-server"));
    }

    @Test
    void generateCompact_escapesHtml() {
        var analysis = buildAnalysis();
        analysis.setLabel("<script>alert('xss')</script>");
        String html = HtmlReportGenerator.generateCompact(analysis);

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void generateComparison_containsAllSections() {
        var statsA = List.of(
                new EndpointStats("UserWS/get", 100, 200.0, 50, 500, 400, 5),
                new EndpointStats("OrderWS/create", 50, 1000.0, 500, 2000, 1800, 10)
        );
        var statsB = List.of(
                new EndpointStats("UserWS/get", 150, 100.0, 30, 300, 250, 2),
                new EndpointStats("OrderWS/create", 60, 1500.0, 600, 3000, 2500, 15),
                new EndpointStats("NewWS/endpoint", 20, 50.0, 10, 100, 80, 0)
        );

        String html = HtmlReportGenerator.generateComparison("Server A", "Server B", statsA, statsB);

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Server A"));
        assertTrue(html.contains("Server B"));
        // Summary
        assertTrue(html.contains("B Faster"));
        assertTrue(html.contains("B Slower"));
        // Insights
        assertTrue(html.contains("Total Calls"));
        assertTrue(html.contains("Weighted Avg"));
        // Endpoints
        assertTrue(html.contains("UserWS/get"));
        assertTrue(html.contains("OrderWS/create"));
        assertTrue(html.contains("NewWS/endpoint"));
        // Verdicts
        assertTrue(html.contains("badge-success"));
        assertTrue(html.contains("badge-error"));
        assertTrue(html.contains("badge-info")); // New endpoint
    }

    @Test
    void generateComparison_emptyLists_producesValidHtml() {
        String html = HtmlReportGenerator.generateComparison("A", "B", List.of(), List.of());

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Endpoint Comparison"));
    }

    @Test
    void generateComparison_escapesLabels() {
        String html = HtmlReportGenerator.generateComparison("<b>evil</b>", "normal", List.of(), List.of());

        assertFalse(html.contains("<b>evil</b>"));
        assertTrue(html.contains("&lt;b&gt;evil&lt;/b&gt;"));
    }

    @Test
    void generateComparison_removedEndpoint_showsRemovedBadge() {
        var statsA = List.of(new EndpointStats("OldWS/get", 100, 200.0, 50, 500, 400, 5));
        var statsB = List.<EndpointStats>of();

        String html = HtmlReportGenerator.generateComparison("A", "B", statsA, statsB);

        assertTrue(html.contains("Removed"));
        assertTrue(html.contains("OldWS/get"));
    }
}
