package br.com.fzdevx.domain.shared;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Minimal 5-field cron parser with zero framework dependencies.
 * Fields: minute hour day-of-month month day-of-week
 * Supports: *, ranges (1-5), lists (1,3,5), steps (&#42;/5), day-of-week 0-6 (0=Sunday).
 */
public final class CronParser {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("^(\\*|[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*)(/[0-9]+)?$");

    private static final int MAX_SEARCH_MINUTES = 366 * 24 * 60; // 1 year + 1 day

    private CronParser() {}

    /**
     * Returns the next execution time after the given instant, or null if
     * no valid execution exists within 366 days.
     */
    public static Instant nextExecution(String cron, Instant after) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have exactly 5 fields.");
        }

        Set<Integer> minutes = parseField(fields[0], 0, 59);
        Set<Integer> hours = parseField(fields[1], 0, 23);
        Set<Integer> daysOfMonth = parseField(fields[2], 1, 31);
        Set<Integer> months = parseField(fields[3], 1, 12);
        Set<Integer> daysOfWeek = parseField(fields[4], 0, 6);

        ZonedDateTime candidate = after.atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1);

        for (int i = 0; i < MAX_SEARCH_MINUTES; i++) {
            if (months.contains(candidate.getMonthValue())
                    && daysOfMonth.contains(candidate.getDayOfMonth())
                    && daysOfWeek.contains(toCronDow(candidate.getDayOfWeek()))
                    && hours.contains(candidate.getHour())
                    && minutes.contains(candidate.getMinute())) {
                return candidate.toInstant();
            }
            candidate = candidate.plusMinutes(1);
        }

        return null; // no match within search window
    }

    /**
     * Validates a 5-field cron expression.
     */
    public static boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) return false;
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) return false;

        int[][] ranges = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 6}};
        for (int i = 0; i < 5; i++) {
            try {
                if (!FIELD_PATTERN.matcher(fields[i]).matches()) return false;
                Set<Integer> values = parseField(fields[i], ranges[i][0], ranges[i][1]);
                if (values.isEmpty()) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> result = new TreeSet<>();

        for (String part : field.split(",")) {
            int step = 1;
            String rangePart = part;

            int slashIdx = part.indexOf('/');
            if (slashIdx >= 0) {
                step = Integer.parseInt(part.substring(slashIdx + 1));
                if (step <= 0) throw new IllegalArgumentException("Step must be positive: " + part);
                rangePart = part.substring(0, slashIdx);
            }

            int rangeStart, rangeEnd;

            if ("*".equals(rangePart)) {
                rangeStart = min;
                rangeEnd = max;
            } else if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                rangeStart = Integer.parseInt(bounds[0]);
                rangeEnd = Integer.parseInt(bounds[1]);
            } else {
                rangeStart = Integer.parseInt(rangePart);
                rangeEnd = rangeStart;
            }

            if (rangeStart < min || rangeEnd > max || rangeStart > rangeEnd) {
                throw new IllegalArgumentException("Invalid range " + rangePart + " for [" + min + "-" + max + "]");
            }

            for (int v = rangeStart; v <= rangeEnd; v += step) {
                result.add(v);
            }
        }
        return result;
    }

    /** Converts Java DayOfWeek (MONDAY=1..SUNDAY=7) to cron (0=Sunday..6=Saturday). */
    private static int toCronDow(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 0 : dow.getValue();
    }
}
