package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Day selection is the whole of this job's logic: the timer is only a trigger,
 * so what matters is which calendar days each run picks. The timer is never
 * started here - summariseNow() is called directly, as with the other scheduled
 * services in this codebase.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivitySummaryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @Mock PersistenceBackendProducer backendProducer;
    @Mock PgUserActivityRepository activityRepository;

    private ActivitySummaryService service;

    @BeforeEach
    void setUp() {
        service = new ActivitySummaryService();
        service.backendProducer = backendProducer;
        service.activityRepository = activityRepository;
        service.enabled = true;
        service.maxDaysPerRun = 90;
        service.zoneId = Optional.of(ZONE.getId());
        when(backendProducer.isPostgres()).thenReturn(true);
    }

    private LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private List<LocalDate> rolledUpDays() {
        ArgumentCaptor<LocalDate> days = ArgumentCaptor.forClass(LocalDate.class);
        verify(activityRepository, org.mockito.Mockito.atLeastOnce())
                .rollUpDay(days.capture(), any(), any());
        return days.getAllValues();
    }

    @Test
    void inactiveOnFileBackend_touchesNothing() {
        when(backendProducer.isPostgres()).thenReturn(false);

        service.summariseNow();

        assertFalse(service.active());
        verifyNoInteractions(activityRepository);
    }

    @Test
    void disabled_touchesNothing() {
        service.enabled = false;

        service.summariseNow();

        verifyNoInteractions(activityRepository);
    }

    @Test
    void emptyAuditTrail_parksTheWatermarkWithoutRollingUp() {
        when(activityRepository.summarisedThrough()).thenReturn(Optional.empty());
        when(activityRepository.oldestAuditEntry()).thenReturn(Optional.empty());

        service.summariseNow();

        verify(activityRepository).setSummarisedThrough(today().minusDays(1));
        verify(activityRepository, never()).rollUpDay(any(), any(), any());
    }

    @Test
    void watermarkAtYesterday_hasNothingToDo() {
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(today().minusDays(1)));

        service.summariseNow();

        // today is still accumulating and must never be summarised
        verify(activityRepository, never()).rollUpDay(any(), any(), any());
    }

    @Test
    void catchesUpEveryCompleteDaySinceTheWatermark() {
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(today().minusDays(4)));

        service.summariseNow();

        // days -3, -2 and -1; never today
        assertEquals(List.of(today().minusDays(3), today().minusDays(2), today().minusDays(1)),
                rolledUpDays());
    }

    @Test
    void firstRunBackfillsFromTheOldestAuditEntry() {
        when(activityRepository.summarisedThrough()).thenReturn(Optional.empty());
        when(activityRepository.oldestAuditEntry()).thenReturn(
                Optional.of(today().minusDays(2).atTime(15, 0).atZone(ZONE).toInstant()));

        service.summariseNow();

        assertEquals(List.of(today().minusDays(2), today().minusDays(1)), rolledUpDays());
    }

    @Test
    void backfillIsCappedPerRun() {
        service.maxDaysPerRun = 2;
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(today().minusDays(30)));

        service.summariseNow();

        verify(activityRepository, times(2)).rollUpDay(any(), any(), any());
        assertEquals(List.of(today().minusDays(29), today().minusDays(28)), rolledUpDays());
    }

    @Test
    void dayBoundsAreHalfOpenAndInTheConfiguredZone() {
        LocalDate day = today().minusDays(1);
        when(activityRepository.summarisedThrough()).thenReturn(Optional.of(day.minusDays(1)));

        service.summariseNow();

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(activityRepository).rollUpDay(any(), start.capture(), end.capture());
        assertEquals(day.atStartOfDay(ZONE).toInstant(), start.getValue());
        assertEquals(day.plusDays(1).atStartOfDay(ZONE).toInstant(), end.getValue());
    }

    @Test
    void repositoryFailureIsSwallowed() {
        // an exception escaping the tick would cancel the scheduled task forever
        when(activityRepository.summarisedThrough()).thenThrow(new IllegalStateException("db down"));

        service.summariseNow();

        assertTrue(true, "summariseNow must not propagate");
    }

    @Test
    void blankZoneFallsBackToTheServerDefault() {
        service.zoneId = Optional.of("   ");
        when(activityRepository.summarisedThrough())
                .thenReturn(Optional.of(LocalDate.now(ZoneId.systemDefault()).minusDays(2)));

        service.summariseNow();

        assertEquals(List.of(LocalDate.now(ZoneId.systemDefault()).minusDays(1)), rolledUpDays());
    }
}
