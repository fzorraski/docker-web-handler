/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — NPE analysis querying with stripping and lookup),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getNpeAnalysis and #getNpeOccurrences
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.NpeLocationSummary;
import br.com.fzdevx.domain.model.NpeOccurrence;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class QueryNpeAnalysisUseCase {

    public PaginatedResult<NpeLocationSummary> querySummaries(List<NpeLocationSummary> npeAnalysis,
                                                              int page, int size) {
        // Paginate first, then strip occurrences only from the page to avoid cloning the full list
        var paginated = PaginatedResult.of(npeAnalysis, page, size);
        var stripped = paginated.data().stream()
                .map(s -> new NpeLocationSummary(s.origin(), s.originClass(), s.method(), s.sourceFile(), s.sourceLine(),
                        s.count(), s.firstSeen(), s.lastSeen(), List.of()))
                .toList();
        return new PaginatedResult<>(stripped, paginated.total(), paginated.page(), paginated.size());
    }

    public PaginatedResult<NpeOccurrence> queryOccurrences(List<NpeLocationSummary> npeAnalysis,
                                                           String origin,
                                                           int page, int size) {
        var summary = npeAnalysis.stream()
                .filter(s -> s.origin().equals(origin)).findFirst();
        if (summary.isEmpty()) {
            return new PaginatedResult<>(List.of(), 0, 0, size);
        }
        return PaginatedResult.of(summary.get().occurrences(), page, size);
    }
}
