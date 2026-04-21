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
        return PaginatedResult.of(applyFilters(allLines, thread, level, search, exclude), page, size);
    }

    /**
     * Resolves which page a specific line number falls on within the filtered results.
     * Uses early termination — stops iterating as soon as the target line is found.
     *
     * @return the zero-indexed page number, or -1 if the line is not in the filtered results
     */
    public int resolvePageForLine(List<LogLine> allLines,
                                  String thread, String level, String search,
                                  String exclude, int lineNumber, int size) {
        size = Math.clamp(size, 1, 15000);
        String searchLower = search != null ? search.toLowerCase() : null;
        List<String> excludePatterns = parseExcludePatterns(exclude);

        int count = 0;
        for (LogLine l : allLines) {
            if (!matchesFilters(l, thread, level, searchLower, excludePatterns)) continue;
            if (l.lineNumber() == lineNumber) return count / size;
            count++;
        }
        return -1;
    }

    private List<LogLine> applyFilters(List<LogLine> allLines,
                                       String thread, String level,
                                       String search, String exclude) {
        String searchLower = search != null ? search.toLowerCase() : null;
        List<String> excludePatterns = parseExcludePatterns(exclude);

        return allLines.stream()
                .filter(l -> matchesFilters(l, thread, level, searchLower, excludePatterns))
                .toList();
    }

    private static List<String> parseExcludePatterns(String exclude) {
        return (exclude != null && !exclude.isBlank())
                ? Arrays.stream(exclude.split(","))
                        .map(s -> s.trim().toLowerCase())
                        .filter(s -> !s.isEmpty())
                        .toList()
                : List.of();
    }

    private static boolean matchesFilters(LogLine l, String thread, String level,
                                          String searchLower, List<String> excludePatterns) {
        if (thread != null && !thread.isBlank() && !thread.equals(l.thread())) return false;
        if (level != null && !level.isBlank() && !LogLevelMatcher.matchesLevelGroup(level, l.level())) return false;
        if (l.message() == null) return searchLower == null;
        String msgLower = l.message().toLowerCase();
        if (searchLower != null && !msgLower.contains(searchLower)) return false;
        if (!excludePatterns.isEmpty()) {
            for (String pattern : excludePatterns) {
                if (msgLower.contains(pattern)) return false;
            }
        }
        return true;
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
