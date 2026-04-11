/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — custom field match querying with filter/sort/paginate),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getCustomFieldMatches
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.CustomFieldMatch;
import br.com.fzdevx.domain.model.CustomFieldResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class QueryCustomFieldsUseCase {

    public record QueryResult(boolean found, boolean countOnly,
                              String fieldName, int matchCount,
                              PaginatedResult<CustomFieldMatch> paginated) {

        public static QueryResult notFound() {
            return new QueryResult(false, false, null, 0, null);
        }

        public static QueryResult countOnly(String fieldName, int matchCount, int size) {
            return new QueryResult(true, true, fieldName, matchCount,
                    new PaginatedResult<>(List.of(), 0, 0, size));
        }

        public static QueryResult withData(String fieldName, int matchCount,
                                            PaginatedResult<CustomFieldMatch> paginated) {
            return new QueryResult(true, false, fieldName, matchCount, paginated);
        }
    }

    public QueryResult execute(List<CustomFieldResult> customFieldResults,
                               String fieldName,
                               String search, String thread,
                               String sort, String sortDir,
                               int page, int size) {
        var result = customFieldResults.stream()
                .filter(cf -> cf.fieldName().equals(fieldName))
                .findFirst();

        if (result.isEmpty()) {
            return QueryResult.notFound();
        }

        CustomFieldResult cfr = result.get();
        if (cfr.countOnly()) {
            return QueryResult.countOnly(cfr.fieldName(), cfr.matchCount(), size);
        }

        String searchTerm = search != null && !search.isBlank() ? search.trim().toLowerCase() : null;
        Stream<CustomFieldMatch> filtered = cfr.matches().stream()
                .filter(m -> thread == null || thread.isBlank() || thread.equals(m.thread()))
                .filter(m -> searchTerm == null || matchesSearch(m, searchTerm));

        if (sort != null && !sort.isBlank()) {
            Comparator<CustomFieldMatch> cmp = switch (sort) {
                case "line" -> Comparator.comparingInt(CustomFieldMatch::lineNumber);
                case "timestamp" -> Comparator.comparing(CustomFieldMatch::timestamp, Comparator.nullsLast(Comparator.naturalOrder()));
                case "thread" -> Comparator.comparing(CustomFieldMatch::thread, Comparator.nullsLast(Comparator.naturalOrder()));
                default -> {
                    // Sort by a named group column value
                    String groupName = sort;
                    yield Comparator.comparing(
                            (CustomFieldMatch m) -> m.groups().getOrDefault(groupName, ""),
                            Comparator.nullsLast(Comparator.naturalOrder()));
                }
            };
            if ("desc".equalsIgnoreCase(sortDir)) cmp = cmp.reversed();
            filtered = filtered.sorted(cmp);
        }

        return QueryResult.withData(cfr.fieldName(), cfr.matchCount(),
                PaginatedResult.of(filtered.toList(), page, size));
    }

    private boolean matchesSearch(CustomFieldMatch m, String term) {
        if (m.fullMessage() != null && m.fullMessage().toLowerCase().contains(term)) return true;
        if (m.thread() != null && m.thread().toLowerCase().contains(term)) return true;
        for (String v : m.groups().values()) {
            if (v != null && v.toLowerCase().contains(term)) return true;
        }
        return false;
    }
}
