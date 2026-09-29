package br.com.fzdevx.domain.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Picks the PostgreSQL error lines out of psql / pg_restore output so a failure
 * can say <em>what</em> went wrong instead of only "exit code 3".
 *
 * <p>Recognised shapes: {@code psql:/path/file.sql:12: ERROR:  relation ... does not exist},
 * {@code pg_restore: error: could not execute query: ERROR:  ...},
 * {@code FATAL:  password authentication failed}. The first few lines are kept
 * verbatim; the rest only count. Safe to feed from a reader thread and read
 * from another once that thread has been joined.</p>
 */
public final class PsqlErrorCollector {

    private static final Pattern ERROR_LINE = Pattern.compile("(?i)(^|\\s)(ERROR|FATAL|PANIC):\\s");
    /** Lines PostgreSQL prints right after an error to explain it. */
    private static final Pattern CONTEXT_LINE =
            Pattern.compile("^\\s*(Command was:|DETAIL:|HINT:|CONTEXT:|STATEMENT:|LINE \\d+:|QUERY:)");
    private static final int MAX_KEPT_LINES = 5;
    private static final int MAX_LINE_LENGTH = 300;

    private final List<String> kept = new ArrayList<>();
    private volatile int total;

    /** True for an error line itself. */
    public static boolean isErrorLine(String line) {
        return line != null && ERROR_LINE.matcher(line).find();
    }

    /** True for an error line or one of the explanatory lines that follow it. */
    public static boolean isErrorOrContextLine(String line) {
        return isErrorLine(line) || (line != null && CONTEXT_LINE.matcher(line).find());
    }

    public void accept(String line) {
        if (!isErrorLine(line)) return;
        synchronized (kept) {
            total++;
            if (kept.size() < MAX_KEPT_LINES) {
                String trimmed = line.strip();
                kept.add(trimmed.length() > MAX_LINE_LENGTH
                        ? trimmed.substring(0, MAX_LINE_LENGTH) + "..." : trimmed);
            }
        }
    }

    public int count() {
        return total;
    }

    public boolean isEmpty() {
        return total == 0;
    }

    /** The kept lines, in order. */
    public List<String> lines() {
        synchronized (kept) {
            return List.copyOf(kept);
        }
    }

    /**
     * One-line human summary, e.g. {@code 3 error(s): ERROR: relation "x" does not exist | ERROR: ...},
     * or null when nothing was collected.
     */
    public String summary() {
        List<String> lines = lines();
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder().append(count()).append(" error(s): ").append(String.join(" | ", lines));
        if (count() > lines.size()) {
            sb.append(" | ... and ").append(count() - lines.size()).append(" more");
        }
        return sb.toString();
    }
}
