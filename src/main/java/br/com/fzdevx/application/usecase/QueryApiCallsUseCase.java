/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — API call querying with filter/sort/search/paginate),
 *        D (depends on no infrastructure classes)
 * Behavior: identical to original LogAnalyzerController#getApiCalls
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.ApiCallPair;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class QueryApiCallsUseCase {

    public PaginatedResult<ApiCallPair> execute(List<ApiCallPair> apiCalls,
                                                String endpoint, String thread,
                                                Long minDuration, String search,
                                                String sort, String sortDir,
                                                int page, int size) {
        String searchTerm = search != null && !search.isBlank() ? search.trim() : null;
        var filtered = apiCalls.stream()
                .filter(c -> endpoint == null || endpoint.isBlank() || c.endpoint().equals(endpoint))
                .filter(c -> thread == null || thread.isBlank() || c.thread().equals(thread))
                .filter(c -> minDuration == null || c.durationMs() >= minDuration)
                .filter(c -> searchTerm == null || containsIgnoreCase(c, searchTerm));

        boolean desc = "desc".equalsIgnoreCase(sortDir);
        Comparator<ApiCallPair> cmp = switch (sort != null ? sort : "time") {
            case "duration" -> Comparator.comparingLong(ApiCallPair::durationMs);
            case "endpoint" -> Comparator.comparing(ApiCallPair::endpoint);
            case "thread" -> Comparator.comparing(ApiCallPair::thread, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(ApiCallPair::requestTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        var sorted = filtered.sorted(desc ? cmp.reversed() : cmp);

        return PaginatedResult.of(sorted.toList(), page, size);
    }

    private boolean containsIgnoreCase(ApiCallPair call, String search) {
        return containsIgnoreCase(call.requestPayload(), search)
                || containsIgnoreCase(call.responsePayload(), search)
                || containsIgnoreCase(call.correlationId(), search);
    }

    private boolean containsIgnoreCase(String text, String search) {
        if (text == null || text.length() < search.length()) return false;
        for (int i = 0, max = text.length() - search.length(); i <= max; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) return true;
        }
        return false;
    }
}
