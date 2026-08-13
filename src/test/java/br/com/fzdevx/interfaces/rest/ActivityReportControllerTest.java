package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ActivityOverview;
import br.com.fzdevx.application.dto.ActivityOverviewCriteria;
import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
import br.com.fzdevx.application.usecase.BuildActivityOverviewUseCase;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.UserActivitySummary;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.persistence.ActivitySummaryService;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityReportControllerTest {

    @Mock PgUserActivityRepository activityRepository;
    @Mock ActivitySummaryService activitySummaryService;
    @Mock BuildActivityOverviewUseCase buildOverview;

    @InjectMocks
    ActivityReportController controller;

    @org.junit.jupiter.api.BeforeEach
    void rollUpIsRunning() {
        when(activitySummaryService.active()).thenReturn(true);
        when(activitySummaryService.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    void classRequiresSystemConfigPermission() {
        // the report spans every tenant, so it is super-admin territory
        RequiresPermission annotation = ActivityReportController.class.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new Permission[]{Permission.SYSTEM_CONFIG}, annotation.value());
    }

    @Test
    void byUser_passesTheParsedRangeThrough() {
        when(activityRepository.totalsByUser(any(), any())).thenReturn(new ActivityReportResult(
                List.of(new UserActivitySummary(null, "alice", "TERMINAL_OPEN", null, 12)), 1));

        Map<String, Object> body = controller.byUser(0, 50, "2026-08-01", "2026-08-31", "alice", null);

        ArgumentCaptor<ActivityReportCriteria> criteria =
                ArgumentCaptor.forClass(ActivityReportCriteria.class);
        org.mockito.Mockito.verify(activityRepository).totalsByUser(criteria.capture(), any());
        assertEquals(LocalDate.of(2026, 8, 1), criteria.getValue().from());
        assertEquals(LocalDate.of(2026, 8, 31), criteria.getValue().to());
        assertEquals("alice", criteria.getValue().actor());
        assertEquals(1L, body.get("total"));
    }

    @Test
    void blankRangeMeansNoFilter() {
        when(activityRepository.search(any(), any())).thenReturn(new ActivityReportResult(List.of(), 0));

        controller.search(0, 50, null, "", null, null);

        ArgumentCaptor<ActivityReportCriteria> criteria =
                ArgumentCaptor.forClass(ActivityReportCriteria.class);
        org.mockito.Mockito.verify(activityRepository).search(criteria.capture(), any());
        assertNull(criteria.getValue().from());
        assertNull(criteria.getValue().to());
    }

    @Test
    void malformedDateIsRejected() {
        assertThrows(InvalidInputException.class,
                () -> controller.search(0, 50, "12/08/2026", null, null, null));
    }

    @Test
    void queryingWithTheRollUpInactiveIsRejectedNotA500() {
        // the summary tables only exist on the postgres backend
        when(activitySummaryService.active()).thenReturn(false);

        assertThrows(InvalidInputException.class,
                () -> controller.byUser(0, 50, null, null, null, null));
        assertThrows(InvalidInputException.class,
                () -> controller.search(0, 50, null, null, null, null));
        assertThrows(InvalidInputException.class,
                () -> controller.overview(null, null, null, 0, 0));
        assertTrue(controller.actions().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(activityRepository);
    }

    @Test
    void status_reportsWhetherTheRollUpRuns() {
        when(activitySummaryService.active()).thenReturn(false);

        assertEquals(false, controller.status().get("active"));
    }

    @Test
    void overview_defaultsToTheLastThirtyDaysEndingToday() {
        // ending today, not yesterday: the query falls through to the audit
        // trail past the watermark, so today has real numbers
        when(activityRepository.summarisedThrough()).thenReturn(Optional.empty());

        controller.overview(null, null, null, 0, 0);

        ArgumentCaptor<ActivityOverviewCriteria> criteria =
                ArgumentCaptor.forClass(ActivityOverviewCriteria.class);
        org.mockito.Mockito.verify(buildOverview).build(any(), criteria.capture(), any());
        LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
        assertEquals(today, criteria.getValue().to());
        assertEquals(today.minusDays(29), criteria.getValue().from());
        assertEquals(30, criteria.getValue().days());
    }

    @Test
    void overview_fetchesThePrecedingWindowInTheSameQuery() {
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(LocalDate.of(2026, 8, 11)));

        controller.overview("2026-08-01", "2026-08-10", "tenant-1", 0, 0);

        ArgumentCaptor<LocalDate> start = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> end = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(activityRepository).rowsForRange(start.capture(), end.capture(),
                org.mockito.ArgumentMatchers.eq("tenant-1"), any());
        // ten days requested, so the fetch reaches ten days further back
        assertEquals(LocalDate.of(2026, 7, 22), start.getValue());
        assertEquals(LocalDate.of(2026, 8, 10), end.getValue());
    }

    @Test
    void overview_rejectsAnInvertedOrOversizedRange() {
        assertThrows(InvalidInputException.class,
                () -> controller.overview("2026-08-10", "2026-08-01", null, 0, 0));
        assertThrows(InvalidInputException.class,
                () -> controller.overview("2020-01-01", "2026-08-01", null, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(buildOverview);
    }

    @Test
    void overview_passesTheRollUpWatermarkThrough() {
        // the screen labels which part of the range is live off this
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(LocalDate.of(2026, 8, 12)));
        when(buildOverview.build(any(), any(), any())).thenReturn(null);

        controller.overview("2026-08-01", "2026-08-13", null, 0, 0);

        ArgumentCaptor<LocalDate> watermark = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(buildOverview).build(any(), any(), watermark.capture());
        assertEquals(LocalDate.of(2026, 8, 12), watermark.getValue());
    }

    @Test
    void overview_returnsWhateverTheUseCaseBuilt() {
        ActivityOverview built = new ActivityOverview(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10), null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(activityRepository.summarisedThrough()).thenReturn(Optional.empty());
        when(buildOverview.build(any(), any(), any())).thenReturn(built);

        assertEquals(built, controller.overview("2026-08-01", "2026-08-10", null, 0, 0));
    }
}
