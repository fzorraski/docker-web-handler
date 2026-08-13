package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ActivityOverview;
import br.com.fzdevx.application.dto.ActivityOverviewCriteria;
import br.com.fzdevx.domain.model.ActivityCategory;
import br.com.fzdevx.domain.model.UserActivitySummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildActivityOverviewUseCaseTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 10);

    private final BuildActivityOverviewUseCase useCase = new BuildActivityOverviewUseCase();

    private final List<UserActivitySummary> rows = new ArrayList<>();

    private void row(LocalDate day, String actor, String action, long count) {
        rows.add(new UserActivitySummary(day, actor, action, "t1", count));
    }

    private ActivityOverview build() {
        return build(new ActivityOverviewCriteria(FROM, TO, null, 0, 0));
    }

    private java.util.Set<String> registered = new java.util.HashSet<>();

    private ActivityOverview build(ActivityOverviewCriteria criteria) {
        return useCase.build(rows, criteria, LocalDate.of(2026, 8, 9), registered);
    }

    @Test
    void totals_splitOperationalWorkFromSigningIn() {
        row(FROM, "alice", "CONTAINER_UPGRADE", 3);
        row(FROM, "alice", "LOGIN", 7);
        row(FROM, "bob", "LOGIN_FAILED", 5);

        ActivityOverview.Totals totals = build().totals();

        assertEquals(15, totals.events());
        assertEquals(3, totals.operational());
        assertEquals(12, totals.auth());
        assertEquals(5, totals.failures());
        // bob only ever failed to sign in: an attempted username, not a user
        assertEquals(1, totals.activeUsers());
        assertEquals(3, totals.distinctActions());
    }

    @Test
    void totals_areEmptyRatherThanNullWhenNothingHappened() {
        ActivityOverview.Totals totals = build().totals();

        assertEquals(0, totals.events());
        assertEquals(0, totals.activeUsers());
        assertNull(totals.busiestDay(), "no day can be the busiest when none had activity");
        assertNull(totals.busiestUser());
    }

    @Test
    void rowsOutsideTheWindowAreIgnoredEntirely() {
        row(FROM.minusDays(40), "alice", "CONTAINER_START", 100);
        row(TO.plusDays(1), "alice", "CONTAINER_START", 100);
        row(FROM, "alice", "CONTAINER_START", 1);

        assertEquals(1, build().totals().events());
    }

    @Test
    void previousWindowIsTheEqualLengthRunUpToTheStart() {
        // ten days requested, so the comparison window is 22-31 July
        row(FROM.minusDays(1), "alice", "CONTAINER_START", 4);
        row(FROM.minusDays(10), "alice", "CONTAINER_START", 6);
        row(FROM.minusDays(11), "alice", "CONTAINER_START", 99);
        row(FROM, "alice", "CONTAINER_START", 1);

        ActivityOverview overview = build();

        assertEquals(1, overview.totals().events());
        assertEquals(10, overview.previous().events(), "the 11-days-back row is outside it");
    }

    @Test
    void ranking_scoresOperationalWorkAndReportsAuthBeside() {
        // bob logs in constantly and does nothing; alice does the work
        row(FROM, "bob", "LOGIN", 50);
        row(FROM, "alice", "CONTAINER_UPGRADE", 3);
        row(FROM, "alice", "DATABASE_RESTORE", 2);

        List<ActivityOverview.UserRank> ranking = build().ranking();

        assertEquals("alice", ranking.get(0).actor(), "sign-ins are not accomplishments");
        assertEquals(5, ranking.get(0).operational());
        assertEquals(0, ranking.get(0).auth());
        assertEquals("bob", ranking.get(1).actor());
        assertEquals(0, ranking.get(1).operational());
        assertEquals(50, ranking.get(1).auth());
    }

    @Test
    void ranking_carriesThePreviousScoreForTheTrendArrow() {
        row(FROM.minusDays(3), "alice", "CONTAINER_START", 8);
        row(FROM.minusDays(3), "alice", "LOGIN", 99);
        row(FROM, "alice", "CONTAINER_START", 2);

        ActivityOverview.UserRank alice = build().ranking().get(0);

        assertEquals(2, alice.operational());
        assertEquals(8, alice.previousOperational(), "the previous score is operational too");
    }

    @Test
    void ranking_reportsActiveDaysSoOneBurstIsNotMistakenForSteadyWork() {
        row(FROM, "alice", "CONTAINER_START", 30);
        row(FROM, "bob", "CONTAINER_START", 10);
        row(FROM.plusDays(1), "bob", "CONTAINER_START", 10);
        row(FROM.plusDays(2), "bob", "CONTAINER_START", 10);

        List<ActivityOverview.UserRank> ranking = build().ranking();

        assertEquals(1, ranking.stream().filter(r -> r.actor().equals("alice")).findFirst()
                .orElseThrow().activeDays());
        assertEquals(3, ranking.stream().filter(r -> r.actor().equals("bob")).findFirst()
                .orElseThrow().activeDays());
    }

    @Test
    void ranking_topActionPrefersRealWorkOverLogins() {
        row(FROM, "alice", "LOGIN", 40);
        row(FROM, "alice", "TERMINAL_OPEN", 2);

        assertEquals("TERMINAL_OPEN", build().ranking().get(0).topAction(),
                "LOGIN as everyone's signature action would say nothing");
    }

    @Test
    void ranking_topActionFallsBackWhenAllThereIsIsAuth() {
        // a real session, not just rejections - sign-in-only actors do not rank
        row(FROM, "bob", "LOGIN", 9);
        row(FROM, "bob", "LOGIN_FAILED", 1);

        assertEquals("LOGIN", build().ranking().get(0).topAction());
    }

    @Test
    void ranking_isCappedAndTracksTheLastActiveDay() {
        for (int i = 0; i < 8; i++) {
            row(FROM.plusDays(i), "user" + i, "CONTAINER_START", 10 - i);
        }

        ActivityOverview overview = build(new ActivityOverviewCriteria(FROM, TO, null, 3, 0));

        assertEquals(3, overview.ranking().size());
        assertEquals("user0", overview.ranking().get(0).actor());
        assertEquals(FROM, overview.ranking().get(0).lastActive());
    }

    @Test
    void daily_hasOnePointPerDayIncludingQuietOnes() {
        row(FROM, "alice", "CONTAINER_START", 2);
        row(TO, "alice", "CONTAINER_START", 3);

        List<ActivityOverview.DayPoint> daily = build().daily();

        assertEquals(10, daily.size(), "a skipped day would draw as sustained activity");
        assertEquals(FROM, daily.get(0).day());
        assertEquals(2, daily.get(0).total());
        assertEquals(0, daily.get(1).total());
        assertEquals(3, daily.get(9).total());
    }

    @Test
    void daily_pointsCarryEveryCategorySoAStackedChartHasNoHoles() {
        row(FROM, "alice", "CONTAINER_START", 2);

        ActivityOverview.DayPoint first = build().daily().get(0);

        assertEquals(ActivityCategory.values().length, first.byCategory().size());
        assertEquals(2L, first.byCategory().get(ActivityCategory.CONTAINER.name()));
        assertEquals(0L, first.byCategory().get(ActivityCategory.DATABASE.name()));
    }

    @Test
    void categories_onlyListWhatActuallyHappened_busiestFirst() {
        row(FROM, "alice", "CONTAINER_START", 2);
        row(FROM, "alice", "LOGIN", 9);

        List<ActivityOverview.CategoryCount> categories = build().categories();

        assertEquals(2, categories.size());
        assertEquals(ActivityCategory.AUTH.name(), categories.get(0).category());
        assertEquals(9, categories.get(0).count());
    }

    @Test
    void topActions_countTheDistinctPeopleBehindThem() {
        row(FROM, "alice", "TERMINAL_OPEN", 4);
        row(FROM, "bob", "TERMINAL_OPEN", 1);
        row(FROM.plusDays(1), "alice", "TERMINAL_OPEN", 1);

        ActivityOverview.ActionCount top = build().topActions().get(0);

        assertEquals("TERMINAL_OPEN", top.action());
        assertEquals(6, top.count());
        assertEquals(2, top.users());
        assertEquals(ActivityCategory.TERMINAL.name(), top.category());
    }

    @Test
    void failures_rankActorsAndKeepTheLastOccurrence() {
        row(FROM, "alice", "RESTORE_FAILED", 2);
        row(TO, "alice", "RESTORE_FAILED", 7);
        row(FROM, "alice", "LOGIN", 5);
        // rejected sign-ins moved to the sign-in attempts report
        row(TO, "unknown", "LOGIN_FAILED", 9);

        List<ActivityOverview.FailureCount> failures = build().failures();

        assertEquals(1, failures.size(), "sign-ins, failed or not, are not operational failures");
        assertEquals("alice", failures.get(0).actor());
        assertEquals(9, failures.get(0).count());
        assertEquals(TO, failures.get(0).lastAt());
    }

    @Test
    void heatmap_coversTheRankedUsersOnly() {
        row(FROM, "alice", "CONTAINER_START", 5);
        row(FROM, "alice", "TERMINAL_OPEN", 1);
        row(FROM, "bob", "CONTAINER_START", 1);

        ActivityOverview overview = build(new ActivityOverviewCriteria(FROM, TO, null, 1, 0));

        assertEquals(1, overview.heatmap().size());
        assertEquals("alice", overview.heatmap().get(0).actor());
        assertEquals(6, overview.heatmap().get(0).count(), "a day's cell sums every action");
    }

    @Test
    void tenants_keepTheUntenantedBucketInsteadOfDroppingIt() {
        rows.add(new UserActivitySummary(FROM, "system", "SETTINGS_UPDATE", null, 3));
        row(FROM, "alice", "CONTAINER_START", 1);

        List<ActivityOverview.TenantCount> tenants = build().tenants();

        assertEquals(2, tenants.size());
        assertEquals(3, tenants.get(0).count());
        assertNull(tenants.get(0).tenantId(), "system actions belong to no tenant");
        assertEquals("t1", tenants.get(1).tenantId());
    }

    @Test
    void windowBoundsAndWatermarkAreEchoedBack() {
        ActivityOverview overview = build();

        assertEquals(FROM, overview.from());
        assertEquals(TO, overview.to());
        assertEquals(LocalDate.of(2026, 8, 9), overview.summarisedThrough());
        assertNotNull(overview.previous());
        assertTrue(overview.ranking().isEmpty());
    }

    @Test
    void aRowWithoutADayIsSkippedRatherThanCrashing() {
        // range totals carry a null day; they must never reach this builder,
        // but one slipping through should not take the dashboard down
        rows.add(new UserActivitySummary(null, "alice", "CONTAINER_START", "t1", 5));
        row(FROM, "alice", "CONTAINER_START", 1);

        assertEquals(1, build().totals().events());
    }

    // ---- sign-in attempts ----

    @Test
    void signInOnlyActors_doNotCountAsActiveUsers_orRank_orFail() {
        row(FROM, "alice", "CONTAINER_CREATE", 2);
        row(FROM, "ama", "LOGIN_FAILED", 3);
        row(FROM, "adm", "LOGIN_FAILED", 1);

        ActivityOverview overview = build();

        // attempted usernames are not users
        assertEquals(1, overview.totals().activeUsers());
        assertTrue(overview.ranking().stream().noneMatch(r -> r.actor().equals("ama")));
        assertTrue(overview.heatmap().stream().noneMatch(c -> c.actor().equals("ama")));
        assertTrue(overview.failures().isEmpty());
        assertEquals("alice", overview.totals().busiestUser());
    }

    @Test
    void signInAttempts_flagUnregisteredNames() {
        registered.add("wesley");
        row(FROM, "wesley", "LOGIN_FAILED", 2);
        row(FROM, "wesley", "LOGIN", 9);
        row(FROM.plusDays(1), "fabricio", "LOGIN_FAILED", 5);

        var attempts = build().signInAttempts();

        assertEquals(2, attempts.size());
        // most failures first
        assertEquals("fabricio", attempts.get(0).actor());
        assertFalse(attempts.get(0).known());
        assertEquals(0, attempts.get(0).successes());
        assertEquals("wesley", attempts.get(1).actor());
        assertTrue(attempts.get(1).known());
        assertEquals(9, attempts.get(1).successes());
    }

    @Test
    void signInAttempts_matchRegisteredNamesCaseInsensitively() {
        registered.add("admin");
        row(FROM, "Admin", "LOGIN_FAILED", 1);

        assertTrue(build().signInAttempts().getFirst().known());
    }

    @Test
    void failuresPanel_excludesRejectedSignIns_evenForRealUsers() {
        row(FROM, "alice", "CONTAINER_CREATE", 1);
        row(FROM, "alice", "LOGIN_FAILED", 4);
        row(FROM, "alice", "RESTORE_FAILED", 2);

        var failures = build().failures();

        assertEquals(1, failures.size());
        assertEquals(2, failures.getFirst().count());
        // ...but the sign-ins still show in their own report
        assertEquals(4, build().signInAttempts().getFirst().failures());
    }
}
