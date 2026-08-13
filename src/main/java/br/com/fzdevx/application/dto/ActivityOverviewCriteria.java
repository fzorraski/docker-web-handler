package br.com.fzdevx.application.dto;

import java.time.LocalDate;

/**
 * The window a dashboard request covers, plus the caps that keep the response
 * bounded. Both dates are inclusive calendar days in the summariser's zone.
 *
 * @param tenantId    only count actions belonging to this tenant; null spans all
 * @param topUsers    how many users the ranking and the heatmap carry
 * @param topActions  how many actions the action breakdown carries
 */
public record ActivityOverviewCriteria(LocalDate from, LocalDate to, String tenantId,
                                       int topUsers, int topActions) {

    /**
     * A dashboard reads a period, not an archive. The cap also bounds the query
     * that backs it: the previous-period comparison doubles the days scanned.
     */
    public static final int MAX_RANGE_DAYS = 366;

    public static final int DEFAULT_TOP_USERS = 15;
    public static final int DEFAULT_TOP_ACTIONS = 12;

    public ActivityOverviewCriteria {
        topUsers = clamp(topUsers, DEFAULT_TOP_USERS, 50);
        topActions = clamp(topActions, DEFAULT_TOP_ACTIONS, 50);
    }

    /** Days in the window, inclusive of both ends. */
    public long days() {
        return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Start of the preceding window of equal length. */
    public LocalDate previousFrom() {
        return from.minusDays(days());
    }

    /** End of the preceding window - the day before this one starts. */
    public LocalDate previousTo() {
        return from.minusDays(1);
    }

    private static int clamp(int value, int fallback, int max) {
        return value <= 0 ? fallback : Math.min(value, max);
    }
}
