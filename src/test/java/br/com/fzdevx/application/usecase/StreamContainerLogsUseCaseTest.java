package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
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
class StreamContainerLogsUseCaseTest {

    private static final String VALID_ID = "abc123def456";

    @Mock DockerContainerPort dockerContainerPort;

    @InjectMocks
    StreamContainerLogsUseCase useCase;

    private List<ContainerEvent> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
    }

    private ContainerEvent lastEvent() {
        assertFalse(events.isEmpty(), "No events captured");
        return events.getLast();
    }

    // ---- validation ----

    @Test
    void execute_nullContainerId_sendsError() {
        useCase.execute(null, events::add, () -> true);

        assertEquals(EventType.ERROR, lastEvent().getType());
        assertEquals("Logs", lastEvent().getStep());
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_blankContainerId_sendsError() {
        useCase.execute("   ", events::add, () -> true);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_invalidContainerId_sendsError() {
        useCase.execute("INVALID!", events::add, () -> true);

        assertEquals(EventType.ERROR, lastEvent().getType());
        verifyNoInteractions(dockerContainerPort);
    }

    // ---- happy path ----

    @Test
    void execute_validId_callsStreamLogs() {
        useCase.execute(VALID_ID, events::add, () -> true);

        verify(dockerContainerPort).streamLogs(eq(VALID_ID), eq(1000), any(), any());
    }

    @Test
    void execute_validId_sendsConnectingEvent() {
        useCase.execute(VALID_ID, events::add, () -> true);

        assertTrue(events.size() >= 2);
        assertEquals(EventType.INFO, events.get(0).getType());
        assertTrue(events.get(0).getMessage().contains("Connecting"));
    }

    @Test
    void execute_validId_sendsSuccessAtEnd() {
        useCase.execute(VALID_ID, events::add, () -> true);

        assertEquals(EventType.SUCCESS, lastEvent().getType());
        assertTrue(lastEvent().getMessage().contains("Log stream ended"));
    }

    @Test
    void execute_passesIsActiveSupplier() {
        useCase.execute(VALID_ID, events::add, () -> false);

        verify(dockerContainerPort).streamLogs(eq(VALID_ID), eq(1000), any(), argThat(
                supplier -> !supplier.get()
        ));
    }
}
