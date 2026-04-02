package br.com.fzdevx.infrastructure.log;

import br.com.fzdevx.domain.model.LogLine;
import br.com.fzdevx.domain.model.NpeLocationSummary;
import br.com.fzdevx.domain.model.NpeOccurrence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class NpeAnalyzer {

    private static final Pattern AT_LINE_PATTERN = Pattern.compile(
            "^\\s+at\\s+(?:\\S+/{1,2})?(.+)\\.(\\w+)\\((\\S+\\.java):(\\d+)\\)"
    );

    private static final Pattern NPE_MESSAGE_PATTERN = Pattern.compile(
            "(?:Caused by:\\s*)?java\\.lang\\.NullPointerException(?::\\s*(.+))?"
    );

    @Inject
    @ConfigProperty(name = "log.analyzer.npe-analysis.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "log.analyzer.npe-analysis.max-occurrences", defaultValue = "5000")
    int maxOccurrences;

    public List<NpeLocationSummary> analyze(List<LogLine> lines) {
        if (!enabled || lines == null || lines.isEmpty()) {
            return List.of();
        }

        // origin key -> list of occurrences
        var groups = new LinkedHashMap<String, List<NpeOccurrence>>();
        // origin key -> origin metadata for building summary
        var originMeta = new LinkedHashMap<String, NpeOccurrence>();
        int totalOccurrences = 0;

        LocalDateTime lastTimestamp = null;

        for (int i = 0; i < lines.size(); i++) {
            if (totalOccurrences >= maxOccurrences) {
                break;
            }

            LogLine line = lines.get(i);

            // Track the most recent timestamp for inheritance
            if (line.timestamp() != null) {
                lastTimestamp = line.timestamp();
            }

            String msg = line.message();
            if (msg == null || !msg.contains("NullPointerException")) {
                continue;
            }

            // Extract NPE message
            Matcher npeMatcher = NPE_MESSAGE_PATTERN.matcher(msg);
            String npeMessage = null;
            if (npeMatcher.find()) {
                npeMessage = npeMatcher.group(1); // may be null if no message after colon
            }

            // Determine timestamp: use line's own or inherit from nearest preceding
            LocalDateTime timestamp = line.timestamp() != null ? line.timestamp() : lastTimestamp;

            // Look ahead for the first "at" line to extract origin
            String originClass = null;
            String method = null;
            String sourceFile = null;
            int sourceLine = 0;

            // Collect stack trace lines
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

                    // Extract origin from first "at" line
                    if (!foundAtLine && trimmed.startsWith("at ")) {
                        Matcher atMatcher = AT_LINE_PATTERN.matcher(nextMsg);
                        if (atMatcher.find()) {
                            String fullClass = atMatcher.group(1);
                            // Handle java.base/ or module prefixes already handled by regex (?:\S+//)?
                            originClass = fullClass;
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

            NpeOccurrence occurrence = new NpeOccurrence(
                    originClass != null ? originClass : "Unknown",
                    method != null ? method : "unknown",
                    sourceFile != null ? sourceFile : "Unknown.java",
                    sourceLine,
                    npeMessage,
                    timestamp,
                    line.lineNumber(),
                    line.sourceFile(),
                    stackTrace
            );

            groups.computeIfAbsent(originKey, _ -> new ArrayList<>()).add(occurrence);
            originMeta.putIfAbsent(originKey, occurrence);
            totalOccurrences++;
        }

        // Build summaries, sorted by count descending
        return groups.entrySet().stream()
                .map(entry -> {
                    String key = entry.getKey();
                    List<NpeOccurrence> occurrences = entry.getValue();
                    NpeOccurrence first = originMeta.get(key);

                    LocalDateTime firstSeen = occurrences.stream()
                            .map(NpeOccurrence::timestamp)
                            .filter(Objects::nonNull)
                            .min(Comparator.naturalOrder())
                            .orElse(null);

                    LocalDateTime lastSeen = occurrences.stream()
                            .map(NpeOccurrence::timestamp)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);

                    return new NpeLocationSummary(
                            key,
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
                .sorted(Comparator.comparingInt(NpeLocationSummary::count).reversed())
                .toList();
    }
}
