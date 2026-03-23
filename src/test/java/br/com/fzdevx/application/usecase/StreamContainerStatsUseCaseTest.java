package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerStats;
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
class StreamContainerStatsUseCaseTest {

    private static final String VALID_ID = "abc123def456";

    @Mock DockerContainerPort dockerContainerPort;

    @InjectMocks
    StreamContainerStatsUseCase useCase;

    private List<ContainerStats> stats;

    @BeforeEach
    void setUp() {
        stats = new ArrayList<>();
    }

    // ---- validation ----

    @Test
    void execute_nullContainerId_doesNotCallPort() {
        useCase.execute(null, stats::add, () -> true);
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_blankContainerId_doesNotCallPort() {
        useCase.execute("   ", stats::add, () -> true);
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_invalidContainerId_doesNotCallPort() {
        useCase.execute("INVALID!", stats::add, () -> true);
        verifyNoInteractions(dockerContainerPort);
    }

    @Test
    void execute_shortContainerId_doesNotCallPort() {
        useCase.execute("abc", stats::add, () -> true);
        verifyNoInteractions(dockerContainerPort);
    }

    // ---- happy path ----

    @Test
    void execute_validId_callsStreamStats() {
        useCase.execute(VALID_ID, stats::add, () -> true);
        verify(dockerContainerPort).streamStats(eq(VALID_ID), any(), any());
    }

    @Test
    void execute_passesIsActiveSupplier() {
        useCase.execute(VALID_ID, stats::add, () -> false);

        verify(dockerContainerPort).streamStats(eq(VALID_ID), any(), argThat(
                supplier -> !supplier.get()
        ));
    }

    @Test
    void execute_passesSinkCorrectly() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var sink = (java.util.function.Consumer<ContainerStats>) invocation.getArgument(1);
            ContainerStats s = new ContainerStats();
            s.setCpuPercent(42.5);
            sink.accept(s);
            return null;
        }).when(dockerContainerPort).streamStats(eq(VALID_ID), any(), any());

        useCase.execute(VALID_ID, stats::add, () -> true);

        assertEquals(1, stats.size());
        assertEquals(42.5, stats.getFirst().getCpuPercent());
    }
}
