package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerSchedulingServiceTest {

    @Mock DockerClient dockerClient;
    @Mock ScheduleRepository scheduleRepository;
    @Mock RunContainerUseCase runContainerUseCase;
    @Mock ResourceCounterService resourceCounterService;
    @Mock MemoryGuardService memoryGuardService;
    @Mock ContainerListBroadcaster broadcaster;

    @InjectMocks
    ContainerSchedulingService service;

    @Mock ListContainersCmd listContainersCmd;

    private ContainerSchedule createStartSchedule(String containerId) {
        ContainerSchedule schedule = new ContainerSchedule("test-start", ScheduleAction.START, ScheduleType.ONE_TIME);
        schedule.setContainerId(containerId);
        return schedule;
    }

    private void invokeExecuteStart(ContainerSchedule schedule) throws Exception {
        Method method = ContainerSchedulingService.class.getDeclaredMethod("executeStart", ContainerSchedule.class);
        method.setAccessible(true);
        method.invoke(service, schedule);
    }

    @BeforeEach
    void setUp() {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.withIdFilter(anyList())).thenReturn(listContainersCmd);
    }

    // ---- executeStart — memory guard ----

    @Test
    void executeStart_memoryGuardBlocked_failsWithMessage() throws Exception {
        Container container = mock(Container.class);
        when(container.getState()).thenReturn("exited");
        when(listContainersCmd.exec()).thenReturn(List.of(container));
        when(memoryGuardService.checkMemoryFor(null)).thenReturn("Insufficient host memory. Available: 500 MB, required: 2048 MB.");

        ContainerSchedule schedule = createStartSchedule("abc123def4");
        when(scheduleRepository.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        invokeExecuteStart(schedule);

        assertEquals("FAILED", schedule.getLastExecutionStatus());
        assertEquals("Insufficient host memory. Available: 500 MB, required: 2048 MB.", schedule.getLastExecutionMessage());
        verify(dockerClient, never()).startContainerCmd(any());
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void executeStart_memoryGuardAllows_startsContainer() throws Exception {
        Container container = mock(Container.class);
        when(container.getState()).thenReturn("exited");
        when(listContainersCmd.exec()).thenReturn(List.of(container));
        when(memoryGuardService.checkMemoryFor(null)).thenReturn(null);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("abc123def4")).thenReturn(startCmd);

        ContainerSchedule schedule = createStartSchedule("abc123def4");
        when(scheduleRepository.findById(schedule.getId())).thenReturn(Optional.of(schedule));

        invokeExecuteStart(schedule);

        assertEquals("SUCCESS", schedule.getLastExecutionStatus());
        verify(startCmd).exec();
    }

    // ---- executeStart — other validations ----

    @Test
    void executeStart_noContainerId_fails() throws Exception {
        ContainerSchedule schedule = createStartSchedule(null);

        invokeExecuteStart(schedule);

        assertEquals("FAILED", schedule.getLastExecutionStatus());
        assertEquals("No container ID configured.", schedule.getLastExecutionMessage());
        verify(dockerClient, never()).startContainerCmd(any());
    }

    @Test
    void executeStart_blankContainerId_fails() throws Exception {
        ContainerSchedule schedule = createStartSchedule("  ");

        invokeExecuteStart(schedule);

        assertEquals("FAILED", schedule.getLastExecutionStatus());
    }

    @Test
    void executeStart_containerNotFound_skipped() throws Exception {
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        ContainerSchedule schedule = createStartSchedule("abc123def4");

        invokeExecuteStart(schedule);

        assertEquals("SKIPPED", schedule.getLastExecutionStatus());
        assertTrue(schedule.getLastExecutionMessage().contains("Container not found"));
    }

    @Test
    void executeStart_containerAlreadyRunning_skipped() throws Exception {
        Container container = mock(Container.class);
        when(container.getState()).thenReturn("running");
        when(listContainersCmd.exec()).thenReturn(List.of(container));

        ContainerSchedule schedule = createStartSchedule("abc123def4");

        invokeExecuteStart(schedule);

        assertEquals("SKIPPED", schedule.getLastExecutionStatus());
        assertEquals("Container is already running.", schedule.getLastExecutionMessage());
        verify(dockerClient, never()).startContainerCmd(any());
    }

    @Test
    void executeStart_dockerException_fails() throws Exception {
        Container container = mock(Container.class);
        when(container.getState()).thenReturn("exited");
        when(listContainersCmd.exec()).thenReturn(List.of(container));
        when(memoryGuardService.checkMemoryFor(null)).thenReturn(null);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("abc123def4")).thenReturn(startCmd);
        when(startCmd.exec()).thenThrow(new RuntimeException("Docker daemon error"));

        ContainerSchedule schedule = createStartSchedule("abc123def4");

        invokeExecuteStart(schedule);

        assertEquals("FAILED", schedule.getLastExecutionStatus());
        assertTrue(schedule.getLastExecutionMessage().contains("Docker daemon error"));
    }

    // ---- transferSchedules ----

    @Test
    void transferSchedules_updatesContainerIdAndSaves() {
        ContainerSchedule schedule = createStartSchedule("oldId12345");
        schedule.setEnabled(false);
        when(scheduleRepository.findByContainerId("oldId12345")).thenReturn(List.of(schedule));

        service.transferSchedules("oldId12345", "newId12345");

        assertEquals("newId12345", schedule.getContainerId());
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void transferSchedules_noSchedules_doesNothing() {
        when(scheduleRepository.findByContainerId("abc123def4")).thenReturn(List.of());

        service.transferSchedules("abc123def4", "newId12345");

        verify(scheduleRepository, never()).save(any());
    }
}
