/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — orphan request querying with filter/paginate),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getOrphanRequests
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.OrphanRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class QueryOrphanRequestsUseCase {

    public PaginatedResult<OrphanRequest> execute(List<OrphanRequest> orphanRequests,
                                                  String endpoint, String thread,
                                                  int page, int size) {
        var orphans = orphanRequests.stream();
        if (endpoint != null && !endpoint.isBlank()) {
            orphans = orphans.filter(o -> endpoint.equals(o.endpoint()));
        }
        if (thread != null && !thread.isBlank()) {
            orphans = orphans.filter(o -> thread.equals(o.thread()));
        }
        return PaginatedResult.of(orphans.toList(), page, size);
    }
}
