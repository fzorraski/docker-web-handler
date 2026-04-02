package br.com.fzdevx.application.dto;

public record AnalysisOptions(
    boolean apiCalls, boolean jobs, boolean failures,
    boolean criticalIssues, boolean npeAnalysis,
    boolean exceptionAnalysis, boolean customFields
) {
    public static AnalysisOptions all() {
        return new AnalysisOptions(true, true, true, true, true, true, true);
    }
}
