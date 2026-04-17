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

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class QueryApiCallsUseCase {

    private static final Pattern JSON_COLON_WS = Pattern.compile("\\s*:\\s*");

    public PaginatedResult<ApiCallPair> execute(List<ApiCallPair> apiCalls,
                                                String endpoint, String thread,
                                                Long minDuration, String search,
                                                String exclude,
                                                LocalDateTime timeFrom, LocalDateTime timeTo,
                                                String sort, String sortDir,
                                                int page, int size) {
        List<String> searchPatterns = (search != null && !search.isBlank())
                ? Arrays.stream(search.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();
        List<String> excludePatterns = (exclude != null && !exclude.isBlank())
                ? Arrays.stream(exclude.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();

        List<String> searchCompact = searchPatterns.stream().map(QueryApiCallsUseCase::compactJson).toList();
        List<String> excludeCompact = excludePatterns.stream().map(QueryApiCallsUseCase::compactJson).toList();

        var filtered = apiCalls.stream()
                .filter(c -> endpoint == null || endpoint.isBlank() || c.endpoint().equals(endpoint))
                .filter(c -> thread == null || thread.isBlank() || c.thread().equals(thread))
                .filter(c -> minDuration == null || c.durationMs() >= minDuration)
                .filter(c -> timeFrom == null || c.requestTimestamp() == null || !c.requestTimestamp().isBefore(timeFrom))
                .filter(c -> timeTo == null || c.requestTimestamp() == null || !c.requestTimestamp().isAfter(timeTo))
                .filter(c -> searchPatterns.isEmpty() || matchesAll(c, searchPatterns, searchCompact))
                .filter(c -> excludePatterns.isEmpty() || matchesNone(c, excludePatterns, excludeCompact));

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

    private boolean matchesAll(ApiCallPair call, List<String> patterns, List<String> compact) {
        for (int i = 0; i < patterns.size(); i++)
            if (!matchesAnyField(call, patterns.get(i), compact.get(i))) return false;
        return true;
    }

    private boolean matchesNone(ApiCallPair call, List<String> patterns, List<String> compact) {
        for (int i = 0; i < patterns.size(); i++)
            if (matchesAnyField(call, patterns.get(i), compact.get(i))) return false;
        return true;
    }

    private boolean matchesAnyField(ApiCallPair call, String pattern, String compact) {
        if (containsIgnoreCase(call.endpoint(), pattern)
                || containsIgnoreCase(call.requestPayload(), pattern)
                || containsIgnoreCase(call.responsePayload(), pattern)
                || containsIgnoreCase(call.correlationId(), pattern)) return true;
        if (compact == null) return false;
        return containsIgnoreCase(call.requestPayload(), compact)
                || containsIgnoreCase(call.responsePayload(), compact);
    }

    private static String compactJson(String s) {
        String compact = JSON_COLON_WS.matcher(s).replaceAll(":");
        return compact.equals(s) ? null : compact;
    }

    private boolean containsIgnoreCase(String text, String search) {
        if (text == null || text.length() < search.length()) return false;
        for (int i = 0, max = text.length() - search.length(); i <= max; i++) {
            if (text.regionMatches(true, i, search, 0, search.length())) return true;
        }
        return false;
    }
}
