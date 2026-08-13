package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
import br.com.fzdevx.domain.model.UserActivitySummary;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PostgresBackendProfile.class)
class PgUserActivityRepositoryTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Inject
    PgUserActivityRepository repository;

    @Inject
    JdbcSupport jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'ACT_TEST%'");
        jdbc.update("DELETE FROM user_activity_daily WHERE action LIKE 'ACT_TEST%'");
        jdbc.update("DELETE FROM activity_summary_state");
    }

    private void audit(String actor, String action, String tenantId, Instant when) {
        jdbc.update("""
                INSERT INTO audit_log (occurred_at, actor, action, target, detail, tenant_id)
                VALUES (?, ?, ?, 'x', NULL, ?)
                """, when, actor, action, tenantId);
    }

    private Instant localTime(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private int rollUp(LocalDate day) {
        return repository.rollUpDay(day,
                day.atStartOfDay(ZONE).toInstant(),
                day.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private ActivityReportCriteria allRows() {
        return new ActivityReportCriteria(null, null, null, "ACT_TEST_RUN", 0, 50);
    }

    private ActivityReportResult search(ActivityReportCriteria criteria) {
        return repository.search(criteria, ZONE);
    }

    @Test
    void rollUp_countsPerActorActionAndTenant() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 10, 0));
        audit("bob", "ACT_TEST_RUN", "t2", localTime(DAY, 11, 0));

        rollUp(DAY);

        List<UserActivitySummary> rows = search(allRows()).rows();
        assertEquals(2, rows.size());
        assertEquals(2, rows.stream().filter(r -> r.actor().equals("alice")).findFirst()
                .orElseThrow().count());
        assertEquals(1, rows.stream().filter(r -> r.actor().equals("bob")).findFirst()
                .orElseThrow().count());
        assertEquals(DAY, rows.get(0).day());
    }

    @Test
    void rollUp_isIdempotent() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 10, 0));

        rollUp(DAY);
        rollUp(DAY);
        rollUp(DAY);

        // the whole design leans on this: a retry, or a second instance firing
        // the same timer, must not inflate the counts
        List<UserActivitySummary> rows = search(allRows()).rows();
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).count());
    }

    @Test
    void rollUp_usesLocalDayBoundariesNotUtc() {
        // 23:30 local on DAY is 02:30 UTC the NEXT day - it must land on DAY
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 23, 30));
        // 00:30 local the next day belongs to the next day, not DAY
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(1), 0, 30));

        rollUp(DAY);

        // window pinned to DAY: past the watermark the next-day entry is
        // visible live, which is the merge working, not the roll-up leaking
        List<UserActivitySummary> rows = search(new ActivityReportCriteria(
                DAY, DAY, null, "ACT_TEST_RUN", 0, 50)).rows();
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count(), "only the entry inside the local day counts");
        assertEquals(DAY, rows.get(0).day());
    }

    @Test
    void rollUp_untenantedEntriesGroupSeparately() {
        audit("system", "ACT_TEST_RUN", null, localTime(DAY, 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 30));

        rollUp(DAY);

        List<UserActivitySummary> rows = search(allRows()).rows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.tenantId() == null && r.actor().equals("system")));
        assertTrue(rows.stream().anyMatch(r -> "t1".equals(r.tenantId())));
    }

    @Test
    void rollUp_nullActorBecomesSystem() {
        audit(null, "ACT_TEST_RUN", null, localTime(DAY, 9, 0));

        rollUp(DAY);

        assertEquals("system", search(allRows()).rows().get(0).actor());
    }

    @Test
    void rollUp_advancesTheWatermarkWithTheData() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));

        rollUp(DAY);

        assertEquals(DAY, repository.summarisedThrough().orElseThrow());
    }

    @Test
    void rollUp_dayWithoutActivityStillAdvancesTheWatermark() {
        // a quiet day writes no rows; if it did not move the watermark the job
        // would rescan it forever
        rollUp(DAY);

        assertEquals(DAY, repository.summarisedThrough().orElseThrow());
    }

    @Test
    void watermark_absentOnFreshInstall() {
        assertTrue(repository.summarisedThrough().isEmpty());
    }

    @Test
    void totalsByUser_sumsAcrossDaysAndDropsTheDay() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(1), 9, 0));
        rollUp(DAY);
        rollUp(DAY.plusDays(1));

        ActivityReportResult totals = repository.totalsByUser(allRows(), ZONE);

        assertEquals(1, totals.total());
        UserActivitySummary row = totals.rows().get(0);
        assertEquals(2, row.count());
        assertNull(row.day(), "a range total has no single day");
    }

    @Test
    void search_filtersByDayRangeAndActor() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("bob", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(2), 9, 0));
        rollUp(DAY);
        rollUp(DAY.plusDays(2));

        var onlyFirstDay = search(new ActivityReportCriteria(
                DAY, DAY, null, "ACT_TEST_RUN", 0, 50));
        assertEquals(1, onlyFirstDay.total());
        assertEquals("alice", onlyFirstDay.rows().get(0).actor());

        var onlyBob = search(new ActivityReportCriteria(
                null, null, "BOB", "ACT_TEST_RUN", 0, 50));
        assertEquals(1, onlyBob.total(), "actor match is case-insensitive");
    }

    @Test
    void search_paginatesWithATotalThatIgnoresThePage() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("bob", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        rollUp(DAY);

        var page = search(new ActivityReportCriteria(null, null, null, "ACT_TEST_RUN", 0, 1));

        assertEquals(2, page.total());
        assertEquals(1, page.rows().size());
    }

    @Test
    void search_readsDaysPastTheWatermarkLiveFromTheAuditTrail() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        rollUp(DAY);
        // never rolled up - without the live merge these would be invisible
        // until the summariser's next run
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(1), 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(1), 10, 0));

        List<UserActivitySummary> rows = search(allRows()).rows();

        assertEquals(2, rows.size());
        assertEquals(DAY.plusDays(1), rows.get(0).day(), "newest first");
        assertEquals(2, rows.get(0).count(), "the live day is aggregated like a summarised one");
        assertEquals(1, rows.get(1).count());
    }

    @Test
    void search_withoutAnyWatermarkEverythingComesLive() {
        // fresh install: the summariser has never run, yet the report works
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 10, 0));

        List<UserActivitySummary> rows = search(allRows()).rows();

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).count());
        assertEquals(DAY, rows.get(0).day());
    }

    @Test
    void liveDaysAreNeverCountedTwice() {
        // the summarised day must come from the summary only - re-reading it
        // live would double every count on the boundary day
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        rollUp(DAY);

        List<UserActivitySummary> rows = search(allRows()).rows();

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).count());
    }

    @Test
    void rowsForRange_mergesSummarisedAndLiveDays() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        rollUp(DAY);
        audit("bob", "ACT_TEST_RUN", "t2", localTime(DAY.plusDays(1), 9, 0));

        List<UserActivitySummary> rows =
                repository.rowsForRange(DAY, DAY.plusDays(1), null, ZONE).stream()
                        .filter(r -> r.action().equals("ACT_TEST_RUN")).toList();

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.actor().equals("alice") && DAY.equals(r.day())));
        assertTrue(rows.stream().anyMatch(r -> r.actor().equals("bob")
                && DAY.plusDays(1).equals(r.day())));
    }

    @Test
    void rowsForRange_filtersByTenantAcrossBothHalves() {
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY, 9, 0));
        audit("bob", "ACT_TEST_RUN", "t2", localTime(DAY, 9, 0));
        rollUp(DAY);
        audit("alice", "ACT_TEST_RUN", "t1", localTime(DAY.plusDays(1), 9, 0));
        audit("bob", "ACT_TEST_RUN", "t2", localTime(DAY.plusDays(1), 9, 0));

        List<UserActivitySummary> rows =
                repository.rowsForRange(DAY, DAY.plusDays(1), "t1", ZONE).stream()
                        .filter(r -> r.action().equals("ACT_TEST_RUN")).toList();

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.actor().equals("alice")),
                "the filter has to hold on the live half too");
    }

    @Test
    void rowsForRange_anInvertedRangeIsEmptyNotAnError() {
        assertTrue(repository.rowsForRange(DAY.plusDays(5), DAY, null, ZONE).isEmpty());
    }

    @Test
    void aggregateOverNoRowsIsEmptyNotAnException() {
        // an aggregate always returns one row, NULL when there is nothing to
        // aggregate. On a fresh install audit_log is empty, so this is the very
        // first thing the summariser asks - it must not blow up.
        assertTrue(jdbc.queryOne("SELECT NULL::timestamptz AS oldest",
                rs -> JdbcSupport.instant(rs, "oldest")).isEmpty());
    }
}
