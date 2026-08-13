package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ActivityOverview;
import br.com.fzdevx.application.dto.ActivityOverviewCriteria;
import br.com.fzdevx.domain.model.ActivityCategory;
import br.com.fzdevx.domain.model.UserActivitySummary;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the flat activity rows into the dashboard's aggregates. Deliberately a
 * pure function over the rows rather than a stack of GROUP BY queries: the
 * source is one row per day, actor, action and tenant, so a whole year is a few
 * thousand rows - cheaper to fold once in memory than to re-scan the range
 * seven times, and testable without a database.
 */
@ApplicationScoped
public class BuildActivityOverviewUseCase {

    /**
     * @param rows              every row from {@link ActivityOverviewCriteria#previousFrom()}
     *                          through {@link ActivityOverviewCriteria#to()} - the
     *                          preceding window comes along so the deltas cost no
     *                          extra query
     * @param summarisedThrough how far the roll-up has got, echoed back so the
     *                          screen can say which part of the range is live
     */
    public ActivityOverview build(List<UserActivitySummary> rows, ActivityOverviewCriteria criteria,
                                  LocalDate summarisedThrough) {
        List<UserActivitySummary> current = new ArrayList<>();
        List<UserActivitySummary> previous = new ArrayList<>();
        for (UserActivitySummary row : rows) {
            if (row.day() == null) {
                continue;
            }
            if (row.day().isBefore(criteria.from())) {
                if (!row.day().isBefore(criteria.previousFrom())) {
                    previous.add(row);
                }
            } else if (!row.day().isAfter(criteria.to())) {
                current.add(row);
            }
        }

        List<ActivityOverview.UserRank> ranking = ranking(current, previous, criteria.topUsers());
        return new ActivityOverview(
                criteria.from(), criteria.to(), summarisedThrough,
                totals(current), totals(previous),
                daily(current, criteria),
                categories(current),
                topActions(current, criteria.topActions()),
                ranking,
                tenants(current),
                failures(current, criteria.topUsers()),
                heatmap(current, ranking));
    }

    // ---- headline numbers ----

    private ActivityOverview.Totals totals(List<UserActivitySummary> rows) {
        long events = 0;
        long operational = 0;
        long auth = 0;
        long failures = 0;
        Set<String> users = new HashSet<>();
        Set<String> actions = new HashSet<>();
        Map<LocalDate, Long> perDay = new HashMap<>();
        Map<String, Long> perUser = new HashMap<>();

        for (UserActivitySummary row : rows) {
            ActivityCategory category = ActivityCategory.of(row.action());
            events += row.count();
            if (category.operational()) {
                operational += row.count();
            } else {
                auth += row.count();
            }
            if (ActivityCategory.failure(row.action())) {
                failures += row.count();
            }
            users.add(row.actor());
            actions.add(row.action());
            perDay.merge(row.day(), row.count(), Long::sum);
            perUser.merge(row.actor(), row.count(), Long::sum);
        }

        // ties break on the later day and the alphabetically first user, so the
        // same data always names the same winner
        Map.Entry<LocalDate, Long> busiestDay = perDay.entrySet().stream()
                .max(Map.Entry.<LocalDate, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .orElse(null);
        Map.Entry<String, Long> busiestUser = perUser.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.<String, Long>comparingByKey().reversed()))
                .orElse(null);

        return new ActivityOverview.Totals(events, operational, auth, failures,
                users.size(), actions.size(),
                busiestDay == null ? null : busiestDay.getKey(),
                busiestDay == null ? 0 : busiestDay.getValue(),
                busiestUser == null ? null : busiestUser.getKey(),
                busiestUser == null ? 0 : busiestUser.getValue());
    }

    // ---- trend ----

    /**
     * One point per day, quiet days included: a line that simply skips them
     * draws a flat segment across a gap and reads as sustained activity.
     */
    private List<ActivityOverview.DayPoint> daily(List<UserActivitySummary> rows,
                                                  ActivityOverviewCriteria criteria) {
        Map<LocalDate, Map<String, Long>> byDay = new HashMap<>();
        Map<LocalDate, Long> totals = new HashMap<>();
        for (UserActivitySummary row : rows) {
            byDay.computeIfAbsent(row.day(), d -> new HashMap<>())
                    .merge(ActivityCategory.of(row.action()).name(), row.count(), Long::sum);
            totals.merge(row.day(), row.count(), Long::sum);
        }

        List<ActivityOverview.DayPoint> points = new ArrayList<>();
        for (LocalDate day = criteria.from(); !day.isAfter(criteria.to()); day = day.plusDays(1)) {
            Map<String, Long> counts = byDay.getOrDefault(day, Map.of());
            points.add(new ActivityOverview.DayPoint(day, totals.getOrDefault(day, 0L),
                    allCategories(counts)));
        }
        return points;
    }

    /** Every category, zeros included, so a stacked chart never sees a hole. */
    private Map<String, Long> allCategories(Map<String, Long> counts) {
        Map<String, Long> complete = new LinkedHashMap<>();
        for (ActivityCategory category : ActivityCategory.values()) {
            complete.put(category.name(), counts.getOrDefault(category.name(), 0L));
        }
        return complete;
    }

    private List<ActivityOverview.CategoryCount> categories(List<UserActivitySummary> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (UserActivitySummary row : rows) {
            counts.merge(ActivityCategory.of(row.action()).name(), row.count(), Long::sum);
        }
        List<ActivityOverview.CategoryCount> result = new ArrayList<>();
        for (ActivityCategory category : ActivityCategory.values()) {
            long count = counts.getOrDefault(category.name(), 0L);
            if (count > 0) {
                result.add(new ActivityOverview.CategoryCount(category.name(), count));
            }
        }
        result.sort(Comparator.comparingLong(ActivityOverview.CategoryCount::count).reversed());
        return result;
    }

    // ---- actions ----

    private List<ActivityOverview.ActionCount> topActions(List<UserActivitySummary> rows, int limit) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Set<String>> actors = new HashMap<>();
        for (UserActivitySummary row : rows) {
            counts.merge(row.action(), row.count(), Long::sum);
            actors.computeIfAbsent(row.action(), a -> new HashSet<>()).add(row.actor());
        }
        return counts.entrySet().stream()
                .map(e -> new ActivityOverview.ActionCount(e.getKey(),
                        ActivityCategory.of(e.getKey()).name(), e.getValue(),
                        actors.getOrDefault(e.getKey(), Set.of()).size()))
                .sorted(Comparator.comparingLong(ActivityOverview.ActionCount::count).reversed()
                        .thenComparing(ActivityOverview.ActionCount::action))
                .limit(limit)
                .toList();
    }

    // ---- people ----

    /**
     * Ranked on operational events only. Counting sign-ins would put whoever
     * has the shortest session timeout on top, which measures the session
     * policy rather than the person.
     */
    private List<ActivityOverview.UserRank> ranking(List<UserActivitySummary> current,
                                                    List<UserActivitySummary> previous, int limit) {
        Map<String, Tally> tallies = new HashMap<>();
        for (UserActivitySummary row : current) {
            tallies.computeIfAbsent(row.actor(), a -> new Tally()).add(row);
        }
        Map<String, Long> previousOperational = new HashMap<>();
        for (UserActivitySummary row : previous) {
            if (ActivityCategory.of(row.action()).operational()) {
                previousOperational.merge(row.actor(), row.count(), Long::sum);
            }
        }

        return tallies.entrySet().stream()
                .map(e -> {
                    Tally tally = e.getValue();
                    return new ActivityOverview.UserRank(e.getKey(), tally.total, tally.operational,
                            tally.auth, tally.failures,
                            previousOperational.getOrDefault(e.getKey(), 0L),
                            allCategories(tally.byCategory), tally.topAction(),
                            tally.days.size(), tally.lastActive);
                })
                .sorted(Comparator.comparingLong(ActivityOverview.UserRank::operational).reversed()
                        .thenComparing(Comparator.comparingLong(ActivityOverview.UserRank::total).reversed())
                        .thenComparing(ActivityOverview.UserRank::actor))
                .limit(limit)
                .toList();
    }

    /** Running per-user counters, folded row by row. */
    private static final class Tally {
        long total;
        long operational;
        long auth;
        long failures;
        final Map<String, Long> byCategory = new HashMap<>();
        final Map<String, Long> byAction = new HashMap<>();
        final Set<LocalDate> days = new HashSet<>();
        LocalDate lastActive;

        void add(UserActivitySummary row) {
            ActivityCategory category = ActivityCategory.of(row.action());
            total += row.count();
            if (category.operational()) {
                operational += row.count();
            } else {
                auth += row.count();
            }
            if (ActivityCategory.failure(row.action())) {
                failures += row.count();
            }
            byCategory.merge(category.name(), row.count(), Long::sum);
            byAction.merge(row.action(), row.count(), Long::sum);
            days.add(row.day());
            if (lastActive == null || row.day().isAfter(lastActive)) {
                lastActive = row.day();
            }
        }

        /**
         * The user's signature action. Operational ones win outright, otherwise
         * everyone's would be LOGIN and the column would say nothing.
         */
        String topAction() {
            return byAction.entrySet().stream()
                    .filter(e -> ActivityCategory.of(e.getKey()).operational())
                    .max(Map.Entry.comparingByValue())
                    .or(() -> byAction.entrySet().stream().max(Map.Entry.comparingByValue()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    private List<ActivityOverview.TenantCount> tenants(List<UserActivitySummary> rows) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Set<String>> actors = new HashMap<>();
        // a null tenant is a real bucket ("system"), and HashMap keys may be null
        for (UserActivitySummary row : rows) {
            counts.merge(String.valueOf(row.tenantId()), row.count(), Long::sum);
            actors.computeIfAbsent(String.valueOf(row.tenantId()), t -> new HashSet<>()).add(row.actor());
        }
        return counts.entrySet().stream()
                .map(e -> new ActivityOverview.TenantCount(
                        "null".equals(e.getKey()) ? null : e.getKey(), e.getValue(),
                        actors.getOrDefault(e.getKey(), Set.of()).size()))
                .sorted(Comparator.comparingLong(ActivityOverview.TenantCount::count).reversed())
                .toList();
    }

    private List<ActivityOverview.FailureCount> failures(List<UserActivitySummary> rows, int limit) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, LocalDate> last = new HashMap<>();
        for (UserActivitySummary row : rows) {
            if (!ActivityCategory.failure(row.action())) {
                continue;
            }
            counts.merge(row.actor(), row.count(), Long::sum);
            last.merge(row.actor(), row.day(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return counts.entrySet().stream()
                .map(e -> new ActivityOverview.FailureCount(e.getKey(), e.getValue(), last.get(e.getKey())))
                .sorted(Comparator.comparingLong(ActivityOverview.FailureCount::count).reversed()
                        .thenComparing(ActivityOverview.FailureCount::actor))
                .limit(limit)
                .toList();
    }

    /**
     * Cells for the ranked users only, and only where something happened - the
     * grid is dense on screen but sparse in the payload, and the ranking has
     * already decided which rows are worth drawing.
     */
    private List<ActivityOverview.HeatCell> heatmap(List<UserActivitySummary> rows,
                                                    List<ActivityOverview.UserRank> ranking) {
        Set<String> shown = new HashSet<>();
        for (ActivityOverview.UserRank rank : ranking) {
            shown.add(rank.actor());
        }
        // a record key, not a concatenated string: an actor name is free text
        // from the audit trail and may hold whatever separator we picked
        record Cell(String actor, LocalDate day) {
        }
        Map<Cell, Long> cells = new LinkedHashMap<>();
        for (UserActivitySummary row : rows) {
            if (shown.contains(row.actor())) {
                cells.merge(new Cell(row.actor(), row.day()), row.count(), Long::sum);
            }
        }
        return cells.entrySet().stream()
                .map(e -> new ActivityOverview.HeatCell(e.getKey().actor(), e.getKey().day(), e.getValue()))
                .toList();
    }
}
