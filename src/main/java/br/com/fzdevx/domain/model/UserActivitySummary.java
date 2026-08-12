package br.com.fzdevx.domain.model;

import java.time.LocalDate;

/**
 * How many times one user performed one action on one day, rolled up from the
 * audit trail. The day is a calendar day in the server's configured zone, not a
 * UTC day.
 *
 * <p>{@code actor} is the username recorded on the audit entries ("system" for
 * unattributed ones); {@code tenantId} is null when the action belonged to no
 * tenant. {@code day} is null on aggregated rows that span a date range.</p>
 */
public record UserActivitySummary(LocalDate day, String actor, String action, String tenantId, long count) {
}
