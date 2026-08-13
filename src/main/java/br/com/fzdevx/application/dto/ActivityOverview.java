package br.com.fzdevx.application.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything the activity dashboard renders, in one response: the flat rows it
 * is built from are useless to a chart, and six round-trips for six widgets
 * would each re-scan the same range.
 *
 * <p>Counts per category are maps keyed by {@link br.com.fzdevx.domain.model.ActivityCategory}
 * name rather than fields, so adding a category does not change this shape.</p>
 *
 * @param summarisedThrough last day covered by the roll-up; days after it come
 *                          straight from the audit trail, which is what keeps
 *                          today on the screen
 * @param previous          the same totals over the immediately preceding
 *                          window of equal length, for period-over-period deltas
 */
public record ActivityOverview(
        LocalDate from,
        LocalDate to,
        LocalDate summarisedThrough,
        Totals totals,
        Totals previous,
        List<DayPoint> daily,
        List<CategoryCount> categories,
        List<ActionCount> topActions,
        List<UserRank> ranking,
        List<TenantCount> tenants,
        List<FailureCount> failures,
        List<SignInAttempt> signInAttempts,
        int signInAttemptsTotal,
        List<HeatCell> heatmap) {

    /**
     * Headline numbers for one window.
     *
     * @param operational everything except {@link br.com.fzdevx.domain.model.ActivityCategory#AUTH}
     * @param busiestDay  null when the window has no activity at all
     */
    public record Totals(
            long events,
            long operational,
            long auth,
            long failures,
            int activeUsers,
            int distinctActions,
            LocalDate busiestDay,
            long busiestDayCount,
            String busiestUser,
            long busiestUserCount) {
    }

    /** One day of the trend chart, split by category. */
    public record DayPoint(LocalDate day, long total, Map<String, Long> byCategory) {
    }

    public record CategoryCount(String category, long count) {
    }

    /** @param users how many distinct people performed it */
    public record ActionCount(String action, String category, long count, int users) {
    }

    /**
     * A user's standing over the window. {@code operational} is the score;
     * {@code auth} and {@code failures} are reported beside it so a noisy
     * sign-in history is visible without inflating the rank.
     *
     * @param previousOperational the same score over the preceding window, for
     *                            the trend arrow
     * @param activeDays          days on which the user did anything, which
     *                            separates steady work from a single burst
     */
    public record UserRank(
            String actor,
            long total,
            long operational,
            long auth,
            long failures,
            long previousOperational,
            Map<String, Long> byCategory,
            String topAction,
            int activeDays,
            LocalDate lastActive) {
    }

    /** @param tenantId null for actions that belonged to no tenant */
    public record TenantCount(String tenantId, long count, int users) {
    }

    /** Operational failures per actor; rejected sign-ins live in {@link SignInAttempt}. */
    public record FailureCount(String actor, long count, LocalDate lastAt) {
    }

    /**
     * One username that failed to sign in during the window. {@code known}
     * says whether the name matches a registered account - a run of failures
     * under an unknown name is credential guessing, not a colleague's typo.
     */
    public record SignInAttempt(String actor, boolean known, long failures, long successes, LocalDate lastAt) {
    }

    /** One cell of the user-by-day heatmap. */
    public record HeatCell(String actor, LocalDate day, long count) {
    }
}
