/**
 * Layer: interfaces/rest/util
 * SOLID: S (single responsibility — maps domain models to REST response maps)
 * Behavior: identical to original LogAnalyzerController#analysisSummaryMap and #presetToMap
 */
package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.domain.model.*;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AnalysisSummaryMapper {

    private AnalysisSummaryMapper() {
    }

    public static Map<String, Object> toSummaryMap(LogAnalysis a) {
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

        var map = new LinkedHashMap<String, Object>();
        map.put("id", a.getId());
        map.put("label", a.getLabel());
        map.put("sourceFiles", a.getSourceFiles());
        map.put("totalLineCount", a.getTotalLineCount());
        map.put("uploadedAt", a.getUploadedAt().toString());
        map.put("timeRangeStart", a.getTimeRangeStart() != null ? a.getTimeRangeStart().toString() : "");
        map.put("timeRangeEnd", a.getTimeRangeEnd() != null ? a.getTimeRangeEnd().toString() : "");
        map.put("threadCount", a.getThreads().size());
        map.put("endpointCount", a.getEndpoints().size());
        map.put("apiCallCount", a.getApiCalls().size());
        map.put("orphanRequestCount", a.getOrphanRequests().size());
        map.put("errorCount", a.getErrors().size());
        map.put("levelCounts", a.getLevelCounts());
        map.put("jobExecutionCount", a.getJobExecutions().size());
        map.put("repeatedFailureCount", a.getRepeatedFailures().size());
        map.put("customFields", customFieldsSummary);
        map.put("criticalIssueCount", criticalIssueCount);
        map.put("criticalIssueSummaries", criticalIssueSummaries);
        map.put("npeAnalysisCount", npeAnalysisCount);
        map.put("npeLocationCount", npeLocationCount);
        map.put("exceptionAnalysisCount", exceptionAnalysisCount);
        map.put("exceptionTypeCount", exceptionTypeCount);
        return map;
    }

    public static Map<String, Object> presetToMap(LogPreset p) {
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
        map.put("criticalIssueExclusions", p.criticalIssueExclusions());
        return map;
    }
}
