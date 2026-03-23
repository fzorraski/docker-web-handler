package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RemoveContainerUseCaseTest {

    private static final String VALID_ID = "abc123def456";

    @Mock DockerContainerPort dockerContainerPort;
    @Mock ContainerExpirationService expirationService;
    @Mock ContainerSchedulingService schedulingService;

    @InjectMocks
    RemoveContainerUseCase useCase;

    private List<ContainerEvent> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
    }

    private ContainerEvent lastEvent() {
        assertFalse(events.isEmpty(), "No events captured");
        return events.getLast();
    }

    private boolean hasEvent(EventType type) {
        return events.stream().anyMatch(e -> e.getType() == type);
    }

    // ---- validation ----

    @Test
    void execute_nullContainerId_sendsError() {
        useCase.execute(null, events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        assertTrue(lastEvent().getMessage().contains("Container ID is required"));
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_blankContainerId_sendsError() {
        useCase.execute("   ", events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_invalidContainerId_sendsError() {
        useCase.execute("INVALID!", events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        assertTrue(lastEvent().getMessage().contains("Invalid container ID"));
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_shortContainerId_sendsError() {
        useCase.execute("abc", events::add);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerContainerPort);
    }

    // ---- happy path ----

    @Test
    void execute_happyPath_sendsSuccessEvent() {
        useCase.execute(VALID_ID, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertFalse(hasEvent(EventType.ERROR));
        assertEquals("Complete", lastEvent().getStep());
        assertTrue(lastEvent().getMessage().contains("removed successfully"));
    }

    @Test
    void execute_happyPath_cancelsExpiration() {
        useCase.execute(VALID_ID, events::add);

        verify(expirationService).cancel(VALID_ID);
    }

    @Test
    void execute_happyPath_stopsContainer() {
        useCase.execute(VALID_ID, events::add);

        verify(dockerContainerPort).stopContainer(VALID_ID);
    }

    @Test
    void execute_happyPath_removesContainer() {
        useCase.execute(VALID_ID, events::add);

        verify(dockerContainerPort).removeContainer(VALID_ID);
    }

    @Test
    void execute_happyPath_cleansUpSchedules() {
        useCase.execute(VALID_ID, events::add);

        verify(schedulingService).removeSchedulesByContainer(VALID_ID);
    }

    // ---- stop failure (should continue) ----

    @Test
    void execute_stopFails_continuesRemoval() {
        doThrow(new RuntimeException("not running")).when(dockerContainerPort).stopContainer(VALID_ID);

        useCase.execute(VALID_ID, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        verify(dockerContainerPort).removeContainer(VALID_ID);
    }

    // ---- remove failure ----

    @Test
    void execute_removeFails_sendsError() {
        doThrow(new RuntimeException("permission denied")).when(dockerContainerPort).removeContainer(VALID_ID);

        useCase.execute(VALID_ID, events::add);

        assertTrue(hasEvent(EventType.ERROR));
        assertFalse(hasEvent(EventType.SUCCESS));
        assertEquals("Removing", lastEvent().getStep());
        assertTrue(lastEvent().getMessage().contains("Failed to remove container"));
    }

    @Test
    void execute_removeFails_doesNotCleanupSchedules() {
        doThrow(new RuntimeException("permission denied")).when(dockerContainerPort).removeContainer(VALID_ID);

        useCase.execute(VALID_ID, events::add);

        verifyNoInteractions(schedulingService);
    }

    // ---- event sequence ----

    @Test
    void execute_happyPath_producesCorrectEventSequence() {
        useCase.execute(VALID_ID, events::add);

        assertEquals(6, events.size());
        // Cancelling (2 INFO), Stopping (2 INFO), Removing (1 INFO), Complete (1 SUCCESS)
        assertEquals("Cancelling", events.get(0).getStep());
        assertEquals("Cancelling", events.get(1).getStep());
        assertEquals("Stopping", events.get(2).getStep());
        assertEquals("Stopping", events.get(3).getStep());
        assertEquals("Removing", events.get(4).getStep());
        assertEquals("Complete", events.get(5).getStep());
        assertEquals(EventType.SUCCESS, events.get(5).getType());
    }
}
