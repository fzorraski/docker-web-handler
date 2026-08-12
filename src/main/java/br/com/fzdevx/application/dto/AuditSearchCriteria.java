package br.com.fzdevx.application.dto;

import java.time.Instant;

/**
 * Filters for browsing the audit trail. Null fields mean "no filter";
 * {@code text} matches target and detail (contains, case-insensitive).
 */
public record AuditSearchCriteria(String actor, String action, String text,
                                  Instant from, Instant to, int page, int size) {

    public static final int MAX_PAGE_SIZE = 100;

    public AuditSearchCriteria {
        page = Math.max(0, page);
        size = size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
    }
}
