package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.application.port.ReportGeneratorPort;
import br.com.fzdevx.domain.model.EndpointStats;
import br.com.fzdevx.domain.model.LogAnalysis;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class HtmlReportGeneratorAdapter implements ReportGeneratorPort {

    @Override
    public String generateCompact(LogAnalysis analysis) {
        return HtmlReportGenerator.generateCompact(analysis);
    }

    @Override
    public String generateComplete(LogAnalysis analysis) {
        return HtmlReportGenerator.generateComplete(analysis);
    }

    @Override
    public String generateComparison(String labelA, String labelB,
                                     List<EndpointStats> statsA, List<EndpointStats> statsB) {
        return HtmlReportGenerator.generateComparison(labelA, labelB, statsA, statsB);
    }
}
