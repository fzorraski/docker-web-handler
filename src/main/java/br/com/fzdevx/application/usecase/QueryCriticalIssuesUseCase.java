/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — critical issue querying, burst computation and lookup),
 *        D (depends on CriticalBurstPort interface, not concrete infra)
 * Behavior: identical to original LogAnalyzerController#getCriticalIssues, #getCriticalBursts,
 *           #getCriticalBurstsByCategory, #getCriticalBurstIssues, and #getOrComputeBursts
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.CriticalBurst;
import br.com.fzdevx.domain.model.CriticalIssue;
import br.com.fzdevx.domain.model.CriticalIssueSummary;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.application.port.CriticalBurstPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class QueryCriticalIssuesUseCase {

    @Inject
    CriticalBurstPort criticalBurstPort;

    public List<CriticalIssueSummary> queryByCategory(List<CriticalIssueSummary> summaries, String category) {
        if (category != null && !category.isBlank()) {
            return summaries.stream()
                    .filter(s -> s.category().equalsIgnoreCase(category))
                    .toList();
        }
        return summaries;
    }

    public List<Map<String, Object>> getBurstSummaries(LogAnalysis analysis,
                                                       Integer threshold, Integer windowMinutes) {
        List<CriticalIssueSummary> withBursts;
        if (threshold != null || windowMinutes != null) {
            // Custom params — bypass cache, compute fresh
            withBursts = criticalBurstPort.computeBursts(analysis.getCriticalIssues(),
                    threshold != null ? Math.clamp(threshold, 2, 1000) : 10,
                    windowMinutes != null ? Math.clamp(windowMinutes, 1, 60) : 5);
        } else {
            withBursts = getOrComputeBursts(analysis);
        }
        // Return only category-level summaries (no individual bursts — those are fetched on demand)
        return withBursts.stream()
                .filter(s -> !s.bursts().isEmpty())
                .map(s -> Map.of(
                        "category", (Object) s.category(),
                        "severity", (Object) s.severity(),
                        "burstCount", (Object) s.bursts().size(),
                        "totalBurstIssues", (Object) s.bursts().stream().mapToInt(CriticalBurst::issueCount).sum(),
                        "firstStart", (Object) s.bursts().stream().map(CriticalBurst::burstStart).filter(Objects::nonNull).min(Comparator.naturalOrder()).map(Object::toString).orElse(""),
                        "lastEnd", (Object) s.bursts().stream().map(CriticalBurst::burstEnd).filter(Objects::nonNull).max(Comparator.naturalOrder()).map(Object::toString).orElse("")
                ))
                .toList();
    }

    public PaginatedResult<Map<String, Object>> getBurstsByCategory(LogAnalysis analysis,
                                                                     String category,
                                                                     int page, int size) {
        var withBursts = getOrComputeBursts(analysis);
        var categorySummary = withBursts.stream()
                .filter(s -> s.category().equalsIgnoreCase(category))
                .findFirst();
        if (categorySummary.isEmpty()) {
            return new PaginatedResult<>(List.of(), 0, 0, size);
        }
        var paginated = PaginatedResult.of(categorySummary.get().bursts(), page, size);
        var burstMetas = paginated.data().stream().map(b -> Map.of(
                "burstStart", (Object)(b.burstStart() != null ? b.burstStart().toString() : ""),
                "burstEnd", (Object)(b.burstEnd() != null ? b.burstEnd().toString() : ""),
                "issueCount", (Object) b.issueCount()
        )).toList();
        return new PaginatedResult<>(burstMetas, paginated.total(), paginated.page(), paginated.size());
    }

    public Optional<PaginatedResult<CriticalIssue>> getBurstIssues(LogAnalysis analysis,
                                                                    String category, int burstIndex,
                                                                    int page, int size) {
        var withBursts = getOrComputeBursts(analysis);
        var categorySummary = withBursts.stream()
                .filter(s -> s.category().equalsIgnoreCase(category))
                .findFirst();
        if (categorySummary.isEmpty() || burstIndex < 0 || burstIndex >= categorySummary.get().bursts().size()) {
            return Optional.empty();
        }
        var burst = categorySummary.get().bursts().get(burstIndex);
        return Optional.of(PaginatedResult.of(burst.issues(), page, size));
    }

    private List<CriticalIssueSummary> getOrComputeBursts(LogAnalysis analysis) {
        var cached = analysis.getCachedBursts();
        if (cached != null) return cached;
        var computed = criticalBurstPort.computeBursts(analysis.getCriticalIssues());
        analysis.setCachedBursts(computed);
        return computed;
    }
}
