/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — API call querying with filter/sort/search/paginate),
 *        D (depends on no infrastructure classes)
 * Behavior: identical to original LogAnalyzerController#getApiCalls
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ApiCallQuery;
import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.ApiCallPair;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class QueryApiCallsUseCase {

    private static final Pattern JSON_COLON_WS = Pattern.compile("\\s*:\\s*");

    public PaginatedResult<ApiCallPair> execute(List<ApiCallPair> apiCalls, ApiCallQuery q) {
        List<String> searchPatterns = (q.search() != null && !q.search().isBlank())
                ? Arrays.stream(q.search().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();
        List<String> excludePatterns = (q.exclude() != null && !q.exclude().isBlank())
                ? Arrays.stream(q.exclude().split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();

        List<String> searchCompact = searchPatterns.stream().map(QueryApiCallsUseCase::compactJson).toList();
        List<String> excludeCompact = excludePatterns.stream().map(QueryApiCallsUseCase::compactJson).toList();

        var filtered = apiCalls.stream()
                .filter(c -> q.endpoint() == null || q.endpoint().isBlank() || containsIgnoreCase(c.endpoint(), q.endpoint()))
                .filter(c -> q.thread() == null || q.thread().isBlank() || c.thread().equals(q.thread()))
                .filter(c -> q.minDuration() == null || c.durationMs() >= q.minDuration())
                .filter(c -> q.maxDuration() == null || c.durationMs() <= q.maxDuration())
                .filter(c -> !q.slowOnly() || c.slow())
                .filter(c -> !q.slowConnectionOnly() || c.slowConnection())
                .filter(c -> q.minConnectionDelay() == null || (c.connectionDelayMs() >= 0 && c.connectionDelayMs() >= q.minConnectionDelay()))
                .filter(c -> q.maxConnectionDelay() == null || (c.connectionDelayMs() >= 0 && c.connectionDelayMs() <= q.maxConnectionDelay()))
                .filter(c -> q.timeFrom() == null || c.requestTimestamp() == null || !c.requestTimestamp().isBefore(q.timeFrom()))
                .filter(c -> q.timeTo() == null || c.requestTimestamp() == null || !c.requestTimestamp().isAfter(q.timeTo()))
                .filter(c -> searchPatterns.isEmpty() || matchesAll(c, searchPatterns, searchCompact))
                .filter(c -> excludePatterns.isEmpty() || matchesNone(c, excludePatterns, excludeCompact));

        boolean desc = "desc".equalsIgnoreCase(q.sortDir());
        Comparator<ApiCallPair> cmp = switch (q.sort() != null ? q.sort() : "time") {
            case "duration" -> Comparator.comparingLong(ApiCallPair::durationMs);
            case "upstream" -> Comparator.comparingLong(ApiCallPair::upstreamDurationMs);
            case "connectionDelay" -> Comparator.comparingLong(ApiCallPair::connectionDelayMs);
            case "endpoint" -> Comparator.comparing(ApiCallPair::endpoint);
            case "thread" -> Comparator.comparing(ApiCallPair::thread, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(ApiCallPair::requestTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        var sorted = filtered.sorted(desc ? cmp.reversed() : cmp);

        return PaginatedResult.of(sorted.toList(), q.page(), q.size());
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
