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

import java.util.List;

@ApplicationScoped
public class QueryLogLinesUseCase {

    public PaginatedResult<LogLine> queryLines(List<LogLine> allLines,
                                               String thread, String level, String search,
                                               int page, int size) {
        String searchLower = search != null ? search.toLowerCase() : null;
        var filtered = allLines.stream()
                .filter(l -> thread == null || thread.isBlank() || thread.equals(l.thread()))
                .filter(l -> level == null || level.isBlank() || LogLevelMatcher.matchesLevelGroup(level, l.level()))
                .filter(l -> searchLower == null || (l.message() != null && l.message().toLowerCase().contains(searchLower)));

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
