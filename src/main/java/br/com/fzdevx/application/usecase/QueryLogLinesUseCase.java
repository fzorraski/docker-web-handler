/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — log line querying with filter/paginate),
 *        D (depends on domain shared LogLevelMatcher)
 * Behavior: identical to original LogAnalyzerController#getLines and #getLineRange
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.shared.LogLevelMatcher;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class QueryLogLinesUseCase {

    public PaginatedResult<LogLine> queryLines(List<LogLine> allLines,
                                               String thread, String level, String search,
                                               String exclude,
                                               int page, int size) {
        String searchLower = search != null ? search.toLowerCase() : null;
        List<String> excludePatterns = (exclude != null && !exclude.isBlank())
                ? Arrays.stream(exclude.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();

        var filtered = allLines.stream()
                .filter(l -> thread == null || thread.isBlank() || thread.equals(l.thread()))
                .filter(l -> level == null || level.isBlank() || LogLevelMatcher.matchesLevelGroup(level, l.level()))
                .filter(l -> {
                    if (l.message() == null) return searchLower == null;
                    String msgLower = l.message().toLowerCase();
                    if (searchLower != null && !msgLower.contains(searchLower)) return false;
                    return excludePatterns.isEmpty() || excludePatterns.stream().noneMatch(msgLower::contains);
                });

        return PaginatedResult.of(filtered.toList(), page, size);
    }

    public PaginatedResult<LogLine> queryLineRange(List<LogLine> allLines,
                                                   int from, int to, String level,
                                                   int page, int size) {
        var rangeLines = allLines.stream()
                .filter(l -> l.lineNumber() >= from && l.lineNumber() <= to)
                .filter(l -> level == null || level.isBlank() || LogLevelMatcher.matchesLevelGroup(level, l.level()))
                .toList();
        return PaginatedResult.of(rangeLines, page, Math.clamp(size, 1, 1000));
    }
}
