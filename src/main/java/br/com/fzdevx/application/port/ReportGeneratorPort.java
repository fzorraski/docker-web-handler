package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.EndpointStats;
import br.com.fzdevx.domain.model.LogAnalysis;

import java.util.List;

public interface ReportGeneratorPort {

    String generateCompact(LogAnalysis analysis);

    String generateComplete(LogAnalysis analysis);

    String generateComparison(String labelA, String labelB,
                              List<EndpointStats> statsA, List<EndpointStats> statsB);
}
