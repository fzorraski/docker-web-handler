package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityReportControllerTest {

    @Mock PgUserActivityRepository activityRepository;
    @Mock ActivitySummaryService activitySummaryService;

    @InjectMocks
    ActivityReportController controller;

    @Test
    void classRequiresSystemConfigPermission() {
        // the report spans every tenant, so it is super-admin territory
        RequiresPermission annotation = ActivityReportController.class.getAnnotation(RequiresPermission.class);
        assertNotNull(annotation);
        assertArrayEquals(new Permission[]{Permission.SYSTEM_CONFIG}, annotation.value());
    }

    @Test
    void byUser_passesTheParsedRangeThrough() {
        when(activityRepository.totalsByUser(any())).thenReturn(new ActivityReportResult(
                List.of(new UserActivitySummary(null, "alice", "TERMINAL_OPEN", null, 12)), 1));

        Map<String, Object> body = controller.byUser(0, 50, "2026-08-01", "2026-08-31", "alice", null);

        ArgumentCaptor<ActivityReportCriteria> criteria =
                ArgumentCaptor.forClass(ActivityReportCriteria.class);
        org.mockito.Mockito.verify(activityRepository).totalsByUser(criteria.capture());
        assertEquals(LocalDate.of(2026, 8, 1), criteria.getValue().from());
        assertEquals(LocalDate.of(2026, 8, 31), criteria.getValue().to());
        assertEquals("alice", criteria.getValue().actor());
        assertEquals(1L, body.get("total"));
    }

    @Test
    void blankRangeMeansNoFilter() {
        when(activityRepository.search(any())).thenReturn(new ActivityReportResult(List.of(), 0));

        controller.search(0, 50, null, "", null, null);

        ArgumentCaptor<ActivityReportCriteria> criteria =
                ArgumentCaptor.forClass(ActivityReportCriteria.class);
        org.mockito.Mockito.verify(activityRepository).search(criteria.capture());
        assertNull(criteria.getValue().from());
        assertNull(criteria.getValue().to());
    }

    @Test
    void malformedDateIsRejected() {
        assertThrows(InvalidInputException.class,
                () -> controller.search(0, 50, "12/08/2026", null, null, null));
    }

    @Test
    void status_reportsWhetherTheRollUpRuns() {
        when(activitySummaryService.active()).thenReturn(false);

        assertEquals(false, controller.status().get("active"));
    }
}
