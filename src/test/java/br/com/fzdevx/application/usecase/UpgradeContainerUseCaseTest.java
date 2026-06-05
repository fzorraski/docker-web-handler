package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.UpgradeContainerRequest;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.docker.PortFinder;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UpgradeContainerUseCaseTest {

    @Mock DockerClient dockerClient;
    @Mock DockerContainerPort dockerContainerPort;
    @Mock RegistryService registryService;
    @Mock br.com.fzdevx.infrastructure.docker.ContainerProtectionService protectionService;
    @Mock ContainerExpirationService expirationService;
    @Mock ExpirationRepository expirationRepository;
    @Mock ContainerSchedulingService schedulingService;
    @Mock PortFinder portFinder;
    @Mock MigrationService migrationService;
    @Mock DatabaseService databaseService;
    @Mock ContainerListBroadcaster broadcaster;
    @Mock org.eclipse.microprofile.config.Config appConfig;
    @Mock br.com.fzdevx.infrastructure.docker.LogRotationResolver logRotationResolver;
    @Mock ManagedDatabaseUsageTracker usageTracker;

    @InjectMocks
    UpgradeContainerUseCase useCase;

    private final List<ContainerEvent> events = new ArrayList<>();
    private final Consumer<ContainerEvent> eventSink = events::add;

    @BeforeEach
    void setUp() {
        events.clear();
        // Default: upgrade enabled for myrepo
        when(appConfig.getOptionalValue("repository.upgrade-enabled.myrepo", Boolean.class))
                .thenReturn(java.util.Optional.of(true));
    }

    private UpgradeContainerRequest tagChangeRequest() {
        UpgradeContainerRequest req = new UpgradeContainerRequest();
        req.setContainerId("abc123def4");
        req.setNewTag("20.88.3");
        return req;
    }

    private UpgradeContainerRequest migrationOnlyRequest() {
        UpgradeContainerRequest req = new UpgradeContainerRequest();
        req.setContainerId("abc123def4");
        req.setMigrationMode("API");
        req.setMigrationSourceVersion("20.30.2");
        req.setMigrationTargetVersion("20.88.3");
        return req;
    }

    @SuppressWarnings("unchecked")
    private void stubInspect() {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("abc123def4")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);

        when(inspect.getName()).thenReturn("/mycontainer");
        when(inspect.getId()).thenReturn("abc123def4fullid1234567890");

        ContainerConfig config = mock(ContainerConfig.class);
        when(inspect.getConfig()).thenReturn(config);
        when(config.getEnv()).thenReturn(new String[]{"DB=test", "PORT=8080"});
        when(config.getImage()).thenReturn("myrepo:20.88.2");

        Map<String, String> labels = new HashMap<>();
        labels.put(Constants.REPOSITORY_LABEL, "myrepo");
        when(config.getLabels()).thenReturn(labels);

        HostConfig hostConfig = mock(HostConfig.class);
        when(inspect.getHostConfig()).thenReturn(hostConfig);
        when(hostConfig.getMemory()).thenReturn(512L * 1024 * 1024);
        when(hostConfig.getPortBindings()).thenReturn(new Ports());
    }

    private void stubDockerOps() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd(anyString())).thenReturn(stopCmd);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        CreateContainerResponse createResp = mock(CreateContainerResponse.class);
        when(createResp.getId()).thenReturn("newid12345fullcontainerabcdef");
        when(createCmd.exec()).thenReturn(createResp);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(anyString())).thenReturn(startCmd);
    }

    // ---- Validation tests ----

    @Test
    void execute_invalidContainerId_emitsError() {
        UpgradeContainerRequest req = new UpgradeContainerRequest();
        req.setContainerId("BAD!");
        req.setNewTag("latest");

        useCase.execute(req, eventSink, null);

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.ERROR));
    }

    @Test
    void execute_protectedContainer_emitsErrorAndDoesNotRemove() {
        stubInspect();
        when(protectionService.isProtectedImage("myrepo:20.88.2")).thenReturn(true);

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("protected")));
        verify(dockerClient, never()).stopContainerCmd(anyString());
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    @Test
    void execute_upgradeDisabled_emitsError() {
        stubInspect();
        when(appConfig.getOptionalValue("repository.upgrade-enabled.myrepo", Boolean.class))
                .thenReturn(java.util.Optional.of(false));

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("not enabled")));
        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void execute_upgradeDisabled_migrationOnlyStillWorks() {
        stubInspect();
        when(appConfig.getOptionalValue("repository.upgrade-enabled.myrepo", Boolean.class))
                .thenReturn(java.util.Optional.of(false));

        ContainerExpiration exp = new ContainerExpiration();
        exp.setShortId("abc123def4");
        exp.setFullContainerId("abc123def4full");
        exp.setRepository("myrepo");
        exp.setDatabaseName("testdb");
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(exp));
        when(databaseService.hasDatabaseConfig("myrepo")).thenReturn(true);
        when(databaseService.getContainerImage("myrepo")).thenReturn("postgres:16");
        when(databaseService.getConnectionInfo("myrepo")).thenReturn(
                new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pass"));
        when(migrationService.orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        useCase.execute(migrationOnlyRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS));
    }

    @Test
    void execute_noTagNoMigration_emitsError() {
        UpgradeContainerRequest req = new UpgradeContainerRequest();
        req.setContainerId("abc123def4");

        useCase.execute(req, eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("No tag change")));
    }

    // ---- Inspect failure ----

    @Test
    void execute_inspectFails_emitsError() {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("abc123def4")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new RuntimeException("Container not found"));

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("inspect")));
    }

    // ---- Successful tag upgrade ----

    @Test
    void execute_tagChange_success() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());
        when(expirationService.findAll()).thenReturn(Collections.emptyList());

        useCase.execute(tagChangeRequest(), eventSink, "ticket-1");

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS));
        verify(dockerClient).stopContainerCmd(anyString());
        verify(dockerClient).removeContainerCmd(anyString());
        verify(dockerClient).createContainerCmd("myrepo:20.88.3");
        verify(dockerClient).startContainerCmd(anyString());
        verify(broadcaster).notifyChange();
    }

    // ---- Migration only (no tag change) ----

    @Test
    void execute_migrationOnly_noContainerRecreation() {
        stubInspect();
        ContainerExpiration exp = new ContainerExpiration();
        exp.setShortId("abc123def4");
        exp.setFullContainerId("abc123def4full");
        exp.setRepository("myrepo");
        exp.setDatabaseName("testdb");
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(exp));
        when(databaseService.hasDatabaseConfig("myrepo")).thenReturn(true);
        when(databaseService.getContainerImage("myrepo")).thenReturn("postgres:16");
        when(databaseService.getConnectionInfo("myrepo")).thenReturn(new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pass"));
        when(migrationService.orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        useCase.execute(migrationOnlyRequest(), eventSink, "ticket-2");

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS));
        // Should NOT stop/remove/create container
        verify(dockerClient, never()).stopContainerCmd(anyString());
        verify(dockerClient, never()).removeContainerCmd(anyString());
        verify(dockerClient, never()).createContainerCmd(anyString());
        // Should call migration
        verify(migrationService).orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---- Tag change with migration ----

    @Test
    void execute_tagChangeWithMigration_bothRun() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());

        ContainerExpiration exp = new ContainerExpiration("abc123def4", "abc123def4full",
                Instant.now().plusSeconds(3600), "myrepo", "testdb", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(exp));
        when(databaseService.hasDatabaseConfig("myrepo")).thenReturn(true);
        when(databaseService.getContainerImage("myrepo")).thenReturn("postgres:16");
        when(databaseService.getConnectionInfo("myrepo")).thenReturn(new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pass"));
        when(migrationService.orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        UpgradeContainerRequest req = tagChangeRequest();
        req.setMigrationMode("API");
        req.setMigrationSourceVersion("20.88.2");
        req.setMigrationTargetVersion("20.88.3");

        useCase.execute(req, eventSink, "ticket-3");

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS));
        verify(dockerClient).createContainerCmd("myrepo:20.88.3");
        verify(migrationService).orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void execute_tagChangeWithMigration_migratesBeforeStart() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());

        ContainerExpiration exp = new ContainerExpiration("abc123def4", "abc123def4full",
                Instant.now().plusSeconds(3600), "myrepo", "testdb", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(exp));
        when(databaseService.hasDatabaseConfig("myrepo")).thenReturn(true);
        when(databaseService.getContainerImage("myrepo")).thenReturn("postgres:16");
        when(databaseService.getConnectionInfo("myrepo")).thenReturn(new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pass"));
        when(migrationService.orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        UpgradeContainerRequest req = tagChangeRequest();
        req.setMigrationMode("API");
        req.setMigrationSourceVersion("20.88.2");
        req.setMigrationTargetVersion("20.88.3");

        useCase.execute(req, eventSink, "ticket-order");

        // Migration must run before container start
        var inOrder = inOrder(migrationService, dockerClient);
        inOrder.verify(migrationService).orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        inOrder.verify(dockerClient).startContainerCmd(anyString());
    }

    // ---- Expiration transfer ----

    @Test
    void execute_transfersExpiration() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());

        Instant expiresAt = Instant.now().plusSeconds(7200);
        ContainerExpiration exp = new ContainerExpiration("abc123def4", "abc123def4full",
                expiresAt, "myrepo", "testdb", true);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(exp));

        useCase.execute(tagChangeRequest(), eventSink, null);

        verify(expirationService).remove("abc123def4");
        verify(expirationService).schedule(eq("newid12345"), anyString(), eq(expiresAt),
                eq("myrepo"), eq("testdb"), eq(true));
    }

    // ---- Schedule transfer ----

    @Test
    void execute_transfersSchedules() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());
        when(expirationService.findAll()).thenReturn(Collections.emptyList());

        useCase.execute(tagChangeRequest(), eventSink, null);

        verify(schedulingService).transferSchedules("abc123def4", "newid12345");
    }

    // ---- Port reuse ----

    @Test
    void execute_withPorts_usesPreferring() {
        stubInspect();
        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(List.of(8080, 8443));
        when(portFinder.getHostPortStart("myrepo")).thenReturn(10000);
        when(portFinder.findAvailablePortsPreferring(anyList(), eq(2), eq(10000))).thenReturn(List.of(9090, 9443));

        useCase.execute(tagChangeRequest(), eventSink, null);

        verify(portFinder).findAvailablePortsPreferring(anyList(), eq(2), eq(10000));
        verify(portFinder).releasePorts(List.of(9090, 9443));
    }

    @Test
    void execute_withPorts_preservesContainerPortToHostPortMapping() {
        // Set up old container with bindings: 8080→9090, 9990→9443
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("abc123def4")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getName()).thenReturn("/mycontainer");
        when(inspect.getId()).thenReturn("abc123def4fullid1234567890");

        ContainerConfig config = mock(ContainerConfig.class);
        when(inspect.getConfig()).thenReturn(config);
        when(config.getEnv()).thenReturn(new String[]{"DB=test"});
        when(config.getImage()).thenReturn("myrepo:20.88.2");
        Map<String, String> labels = new HashMap<>();
        labels.put(Constants.REPOSITORY_LABEL, "myrepo");
        when(config.getLabels()).thenReturn(labels);

        Ports oldPorts = new Ports();
        oldPorts.bind(ExposedPort.tcp(8080), Ports.Binding.bindPort(9090));
        oldPorts.bind(ExposedPort.tcp(9990), Ports.Binding.bindPort(9443));

        HostConfig hostConfig = mock(HostConfig.class);
        when(inspect.getHostConfig()).thenReturn(hostConfig);
        when(hostConfig.getMemory()).thenReturn(512L * 1024 * 1024);
        when(hostConfig.getPortBindings()).thenReturn(oldPorts);

        stubDockerOps();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(List.of(8080, 9990));
        when(portFinder.getHostPortStart("myrepo")).thenReturn(10000);
        when(portFinder.findAvailablePortsPreferring(anyList(), eq(2), eq(10000))).thenReturn(List.of(9090, 9443));

        useCase.execute(tagChangeRequest(), eventSink, null);

        // Preferred list must be [9090, 9443] — matching the order of container ports [8080, 9990]
        verify(portFinder).findAvailablePortsPreferring(eq(List.of(9090, 9443)), eq(2), eq(10000));
    }

    // ---- Pull failure ----

    @Test
    void execute_pullFails_emitsError() throws Exception {
        stubInspect();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        doThrow(new RuntimeException("Pull failed")).when(dockerContainerPort)
                .pullImage(anyString(), anyString(), anyString(), any());

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("pull")));
        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    // ---- Start failure after remove ----

    @Test
    void execute_startFails_emitsErrorWithWarning() {
        stubInspect();
        when(registryService.buildFullImageRef("myrepo", "20.88.3")).thenReturn("myrepo:20.88.3");
        when(portFinder.getContainerPorts("myrepo")).thenReturn(Collections.emptyList());

        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd(anyString())).thenReturn(stopCmd);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeCmd);

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        CreateContainerResponse createResp = mock(CreateContainerResponse.class);
        when(createResp.getId()).thenReturn("newid1234567890abcdef1234");
        when(createCmd.exec()).thenReturn(createResp);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(anyString())).thenReturn(startCmd);
        doThrow(new RuntimeException("Port conflict")).when(startCmd).exec();

        // Mock remove for cleanup of failed new container
        RemoveContainerCmd cleanupRemoveCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("newid1234567890abcdef1234")).thenReturn(cleanupRemoveCmd);

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("removed")));
    }

    // ---- Cancellation ----

    @Test
    void cancel_setsFlag() {
        assertFalse(useCase.cancel("unknown-ticket"));
    }

    // ---- No repository label ----

    @Test
    void execute_noRepositoryLabel_emitsError() {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("abc123def4")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getName()).thenReturn("/mycontainer");

        ContainerConfig config = mock(ContainerConfig.class);
        when(inspect.getConfig()).thenReturn(config);
        when(config.getLabels()).thenReturn(Collections.emptyMap());
        when(config.getEnv()).thenReturn(new String[0]);

        HostConfig hostConfig = mock(HostConfig.class);
        when(inspect.getHostConfig()).thenReturn(hostConfig);
        when(hostConfig.getPortBindings()).thenReturn(new Ports());

        useCase.execute(tagChangeRequest(), eventSink, null);

        assertTrue(events.stream().anyMatch(e ->
                e.getType() == ContainerEvent.EventType.ERROR && e.getMessage().contains("repository")));
    }
}
