/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — exception analysis querying with stripping and lookup),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getExceptionAnalysis and #getExceptionOccurrences
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.ExceptionLocationSummary;
import br.com.fzdevx.domain.model.ExceptionOccurrence;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class QueryExceptionAnalysisUseCase {

    public PaginatedResult<ExceptionLocationSummary> querySummaries(List<ExceptionLocationSummary> exceptionAnalysis,
                                                                    int page, int size) {
        // Paginate first, then strip occurrences only from the page to avoid cloning the full list
        var paginated = PaginatedResult.of(exceptionAnalysis, page, size);
        var stripped = paginated.data().stream()
                .map(s -> new ExceptionLocationSummary(s.exceptionType(), s.origin(), s.originClass(), s.method(), s.sourceFile(), s.sourceLine(),
                        s.count(), s.firstSeen(), s.lastSeen(), List.of()))
                .toList();
        return new PaginatedResult<>(stripped, paginated.total(), paginated.page(), paginated.size());
    }

    public PaginatedResult<ExceptionOccurrence> queryOccurrences(List<ExceptionLocationSummary> exceptionAnalysis,
                                                                  String origin,
                                                                  int page, int size) {
        var summary = exceptionAnalysis.stream()
                .filter(s -> (s.exceptionType() + ":" + s.origin()).equals(origin) || s.origin().equals(origin)).findFirst();
        if (summary.isEmpty()) {
            return new PaginatedResult<>(List.of(), 0, 0, size);
        }
        return PaginatedResult.of(summary.get().occurrences(), page, size);
    }
}
