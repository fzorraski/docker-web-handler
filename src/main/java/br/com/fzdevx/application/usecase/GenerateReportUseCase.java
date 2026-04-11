/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — HTML report generation),
 *        D (depends on ReportGeneratorPort interface)
 * Behavior: identical to original LogAnalyzerController#downloadReport and #compareStats
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.ReportGeneratorPort;
import br.com.fzdevx.domain.model.EndpointStats;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.shared.AnalysisLabelResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class GenerateReportUseCase {

    @Inject
    ReportGeneratorPort reportGenerator;

    public Optional<String> generateReport(LogAnalysis analysis, String type) {
        String html = switch (type) {
            case "compact" -> reportGenerator.generateCompact(analysis);
            case "complete" -> reportGenerator.generateComplete(analysis);
            default -> null;
        };
        return Optional.ofNullable(html);
    }

    public String generateComparison(String labelA, String labelB,
                                     List<EndpointStats> statsA, List<EndpointStats> statsB) {
        return reportGenerator.generateComparison(labelA, labelB, statsA, statsB);
    }

    public String buildReportFilename(LogAnalysis analysis, String type) {
        return AnalysisLabelResolver.toSafeFilenamePrefix(analysis) + "-" + type + ".html";
    }
}
