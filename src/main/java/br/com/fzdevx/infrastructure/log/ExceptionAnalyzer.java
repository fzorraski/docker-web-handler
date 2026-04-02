package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.ExceptionLocationSummary;
import br.com.fzdevx.domain.model.ExceptionOccurrence;
import br.com.fzdevx.domain.model.LogLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ExceptionAnalyzer {

    private static final List<String> EXCEPTION_NAMES = List.of(
            "ClassCastException",
            "IllegalArgumentException",
            "IllegalStateException",
            "ConcurrentModificationException",
            "ArrayIndexOutOfBoundsException",
            "StringIndexOutOfBoundsException",
            "IndexOutOfBoundsException",
            "NumberFormatException",
            "UnsupportedOperationException",
            "ArithmeticException",
            "SecurityException",
            "FileNotFoundException",
            "IOException",
            "SQLException",
            "TimeoutException",
            "InterruptedException"
    );

    private static final Pattern AT_LINE_PATTERN = Pattern.compile(
            "^\\s+at\\s+(?:\\S+/{1,2})?(.+)\\.(\\w+)\\((\\S+\\.java):(\\d+)\\)"
    );

    // Build a combined pattern for all exception types.
    // Matches: optional "Caused by: " prefix, optional package prefix, exception name, optional ": message"
    private static final Pattern EXCEPTION_PATTERN;

    // For IOException: only match when preceded by "Caused by:"
    private static final Pattern IO_EXCEPTION_CAUSED_BY_PATTERN = Pattern.compile(
            "Caused by:\\s*(?:java\\.\\w+\\.)*IOException(?::\\s*(.+))?"
    );

    static {
        String alternation = String.join("|", EXCEPTION_NAMES.stream()
                .filter(name -> !"IOException".equals(name))
                .toList());
        EXCEPTION_PATTERN = Pattern.compile(
                "(?:Caused by:\\s*)?(?:java\\.\\w+\\.)*(" + alternation + ")(?::\\s*(.+))?"
        );
    }

    @Inject
    @ConfigProperty(name = "log.analyzer.exception-analysis.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "log.analyzer.exception-analysis.max-occurrences", defaultValue = "5000")
    int maxOccurrences;

    public List<ExceptionLocationSummary> analyze(List<LogLine> lines) {
        if (!enabled || lines == null || lines.isEmpty()) {
            return List.of();
        }

        // groupKey -> list of occurrences
        var groups = new LinkedHashMap<String, List<ExceptionOccurrence>>();
        // groupKey -> first occurrence metadata
        var originMeta = new LinkedHashMap<String, ExceptionOccurrence>();
        int totalOccurrences = 0;

        LocalDateTime lastTimestamp = null;

        for (int i = 0; i < lines.size(); i++) {
            if (totalOccurrences >= maxOccurrences) {
                break;
            }

            LogLine line = lines.get(i);

            if (line.timestamp() != null) {
                lastTimestamp = line.timestamp();
            }

            String msg = line.message();
            if (msg == null) {
                continue;
            }

            // Skip NullPointerException (handled by NpeAnalyzer)
            if (msg.contains("NullPointerException")) {
                continue;
            }

            // Try to match an exception
            String exceptionType = null;
            String exceptionMessage = null;

            // Check IOException separately (only in "Caused by:" context)
            if (msg.contains("IOException")) {
                Matcher ioMatcher = IO_EXCEPTION_CAUSED_BY_PATTERN.matcher(msg);
                if (ioMatcher.find()) {
                    exceptionType = "IOException";
                    exceptionMessage = ioMatcher.group(1);
                }
            }

            // Check all other exceptions
            if (exceptionType == null) {
                Matcher matcher = EXCEPTION_PATTERN.matcher(msg);
                if (matcher.find()) {
                    exceptionType = matcher.group(1);
                    exceptionMessage = matcher.group(2);
                }
            }

            if (exceptionType == null) {
                continue;
            }

            LocalDateTime timestamp = line.timestamp() != null ? line.timestamp() : lastTimestamp;

            // Look ahead for stack trace and extract origin from first "at" line
            String originClass = null;
            String method = null;
            String sourceFile = null;
            int sourceLine = 0;

            List<String> stackTrace = new ArrayList<>();
            int j = i + 1;
            boolean foundAtLine = false;

            while (j < lines.size()) {
                LogLine nextLine = lines.get(j);
                String nextMsg = nextLine.message();
                if (nextMsg == null) {
                    break;
                }

                String trimmed = nextMsg.trim();
                if (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:") || trimmed.equals("...")) {
                    stackTrace.add(nextMsg);

                    if (!foundAtLine && trimmed.startsWith("at ")) {
                        Matcher atMatcher = AT_LINE_PATTERN.matcher(nextMsg);
                        if (atMatcher.find()) {
                            originClass = atMatcher.group(1);
                            method = atMatcher.group(2);
                            sourceFile = atMatcher.group(3);
                            sourceLine = Integer.parseInt(atMatcher.group(4));
                            foundAtLine = true;
                        }
                    }
                    j++;
                } else {
                    break;
                }
            }

            // Build origin key
            String simpleClassName = originClass != null
                    ? originClass.substring(originClass.lastIndexOf('.') + 1)
                    : "Unknown";
            String originKey = foundAtLine
                    ? simpleClassName + "." + method + ":" + sourceLine
                    : "Unknown";

            // Group key includes exception type
            String groupKey = exceptionType + ":" + originKey;

            ExceptionOccurrence occurrence = new ExceptionOccurrence(
                    exceptionType,
                    originClass != null ? originClass : "Unknown",
                    method != null ? method : "unknown",
                    sourceFile != null ? sourceFile : "Unknown.java",
                    sourceLine,
                    exceptionMessage,
                    timestamp,
                    line.lineNumber(),
                    line.sourceFile(),
                    stackTrace
            );

            groups.computeIfAbsent(groupKey, _ -> new ArrayList<>()).add(occurrence);
            originMeta.putIfAbsent(groupKey, occurrence);
            totalOccurrences++;
        }

        // Build summaries, sorted by count descending
        return groups.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    List<ExceptionOccurrence> occurrences = entry.getValue();
                    ExceptionOccurrence first = originMeta.get(key);

                    // Extract origin part (after the exceptionType: prefix)
                    String origin = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;

                    LocalDateTime firstSeen = occurrences.stream()
                            .map(ExceptionOccurrence::timestamp)
                            .filter(Objects::nonNull)
                            .min(Comparator.naturalOrder())
                            .orElse(null);

                    LocalDateTime lastSeen = occurrences.stream()
                            .map(ExceptionOccurrence::timestamp)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);

                    return new ExceptionLocationSummary(
                            first.exceptionType(),
                            origin,
                            first.originClass(),
                            first.method(),
                            first.sourceFile(),
                            first.sourceLine(),
                            occurrences.size(),
                            firstSeen,
                            lastSeen,
                            occurrences
                    );
                })
                .sorted(Comparator.comparingInt(ExceptionLocationSummary::count).reversed())
                .toList();
    }
}
