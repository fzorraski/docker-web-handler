/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — builds API stats export payload),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#exportApiStats
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.shared.AnalysisLabelResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class ExportApiStatsUseCase {

    public Map<String, Object> execute(LogAnalysis analysis) {
        var export = new LinkedHashMap<String, Object>();
        export.put("version", 1);
        export.put("label", AnalysisLabelResolver.resolve(analysis));
        export.put("exportedAt", Instant.now().toString());
        export.put("timeRangeStart", analysis.getTimeRangeStart() != null ? analysis.getTimeRangeStart().toString() : null);
        export.put("timeRangeEnd", analysis.getTimeRangeEnd() != null ? analysis.getTimeRangeEnd().toString() : null);
        export.put("endpoints", analysis.getEndpointStats());
        return export;
    }

    public String buildFilename(LogAnalysis analysis) {
        return AnalysisLabelResolver.toSafeFilenamePrefix(analysis) + "-stats.json";
    }
}
