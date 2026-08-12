package br.com.fzdevx.application.dto;

import java.time.LocalDate;

/**
 * Filters for the activity reports. Null fields mean "no filter"; {@code from}
 * and {@code to} are inclusive calendar days in the summariser's zone.
 */
public record ActivityReportCriteria(LocalDate from, LocalDate to, String actor, String action,
                                     int page, int size) {

    public static final int MAX_PAGE_SIZE = 200;

    public ActivityReportCriteria {
        page = Math.max(0, page);
        size = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    }
}
