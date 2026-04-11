/**
 * Layer: domain/shared
 * SOLID: S (single responsibility — log level grouping only)
 * Behavior: identical to original LogAnalyzerController#matchesLevelGroup
 */
package br.com.fzdevx.domain.shared;

public final class LogLevelMatcher {

    private LogLevelMatcher() {
    }

    public static boolean matchesLevelGroup(String filter, String lineLevel) {
        if (lineLevel == null) return false;
        return switch (filter.toUpperCase()) {
            case "ERROR" -> "ERROR".equals(lineLevel) || "FATAL".equals(lineLevel) || "SEVERE".equals(lineLevel);
            case "WARN" -> "WARN".equals(lineLevel) || "WARNING".equals(lineLevel);
            default -> filter.equalsIgnoreCase(lineLevel);
        };
    }
}
