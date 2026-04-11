/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — thread summary aggregation),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getThreads
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.LogLine;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class GetThreadSummaryUseCase {

    public List<Map<String, Object>> execute(List<LogLine> allLines) {
        var threadCounts = allLines.stream()
                .filter(l -> l.thread() != null)
                .collect(Collectors.groupingBy(LogLine::thread, Collectors.counting()));

        return threadCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Map.of("thread", (Object) e.getKey(), "lineCount", (Object) e.getValue()))
                .toList();
    }
}
