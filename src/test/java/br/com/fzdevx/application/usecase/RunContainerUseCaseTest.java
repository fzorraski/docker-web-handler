package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.docker.PortFinder;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.PullImageResultCallback;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunContainerUseCaseTest {

    private static final String REPO = "myrepo";
    private static final String TAG = "latest";
    private static final String IMAGE_REF = "myrepo:latest";
    private static final String CONTAINER_ID = "abc123def456";

    @Mock DockerClient dockerClient;
    @Mock DockerContainerPort dockerContainerPort;
    @Mock RegistryService registryService;
    @Mock ContainerExpirationService expirationService;
    @Mock PortFinder portFinder;
    @Mock RestoreDumpUseCase restoreDumpUseCase;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;
    @Mock Config config;
    @Mock MigrationService migrationService;
    @Mock DatabaseService databaseService;
    @Mock DumpStorageService dumpStorageService;
    @Mock ResourceCounterService resourceCounterService;
    @Mock MemoryGuardService memoryGuardService;
    @Mock br.com.fzdevx.infrastructure.docker.LogRotationResolver logRotationResolver;
    @Mock ManagedDatabaseUsageTracker usageTracker;
    @Mock br.com.fzdevx.application.port.AuditLogger auditLogger;
    @Mock br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    @InjectMocks
    RunContainerUseCase useCase;

    private List<ContainerEvent> events;

    @BeforeEach
    void setUp() throws Exception {
        events = new ArrayList<>();
        java.lang.reflect.Field maxMbField = RunContainerUseCase.class.getDeclaredField("memoryLimitMaxMb");
        maxMbField.setAccessible(true);
        maxMbField.setLong(useCase, 65536L);
    }

    // ---- helpers ----

    private RunContainerRequest validRequest() {
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository(REPO);
        req.setTag(TAG);
        req.setContainerName("my-container");
        return req;
    }

    /**
     * Stubs pull to immediately complete via the DockerContainerPort.
     */
    private void stubPullSuccess() throws InterruptedException {
        // pullImage on the port is void — just don't throw
        doNothing().when(dockerContainerPort).pullImage(eq(IMAGE_REF), eq(REPO), eq(TAG), any());
    }

    private void stubHappyPath() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);

        try { stubPullSuccess(); } catch (InterruptedException ignored) {}


        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(IMAGE_REF)).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn(CONTAINER_ID);
        when(createCmd.exec()).thenReturn(response);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

        when(portFinder.getContainerPorts(REPO)).thenReturn(Collections.emptyList());
        when(config.getOptionalValue("repository.hidden-env." + REPO, String.class))
                .thenReturn(Optional.empty());

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);
    }

    private ContainerEvent lastEvent() {
        assertFalse(events.isEmpty(), "No events captured");
        return events.getLast();
    }

    private void assertLastEventError(String step, String messageContains) {
        ContainerEvent last = lastEvent();
        assertEquals(EventType.ERROR, last.getType(),
                "Expected ERROR but was " + last.getType() + ": " + last.getMessage());
        assertEquals(step, last.getStep());
        assertTrue(last.getMessage().contains(messageContains),
                "Expected message containing '" + messageContains + "' but was: " + last.getMessage());
    }

    private boolean hasEvent(EventType type) {
        return events.stream().anyMatch(e -> e.getType() == type);
    }

    private void assertHasError(String step, String messageContains) {
        ContainerEvent error = events.stream()
                .filter(e -> e.getType() == EventType.ERROR && step.equals(e.getStep()))
                .findFirst()
                .orElse(null);
        assertNotNull(error, "Expected ERROR event at step '" + step + "'");
        assertTrue(error.getMessage().contains(messageContains),
                "Expected message containing '" + messageContains + "' but was: " + error.getMessage());
    }

    // ---- validation: repository ----

    @Test
    void execute_nullRepository_sendsError() {
        RunContainerRequest req = validRequest();
        req.setRepository(null);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Repository name is required");
    }

    @Test
    void execute_invalidRepositoryChars_sendsError() {
        RunContainerRequest req = validRequest();
        req.setRepository("UPPERCASE");

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "invalid characters");
    }

    // ---- validation: tag ----

    @Test
    void execute_nullTag_sendsError() {
        RunContainerRequest req = validRequest();
        req.setTag(null);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Tag is required");
    }

    @Test
    void execute_invalidTag_sendsError() {
        RunContainerRequest req = validRequest();
        req.setTag("!!!bad");

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "invalid characters");
    }

    // ---- validation: container name ----

    @Test
    void execute_invalidContainerName_sendsError() {
        RunContainerRequest req = validRequest();
        req.setContainerName("!!!invalid");

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "invalid characters");
    }

    // ---- validation: env vars ----

    @Test
    void execute_invalidEnvVar_sendsError() {
        RunContainerRequest req = validRequest();
        req.setEnvVars(List.of("BAD-KEY=value"));

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Invalid environment variable key");
    }

    @Test
    void execute_envVarMissingEquals_sendsError() {
        RunContainerRequest req = validRequest();
        req.setEnvVars(List.of("NOEQUALS"));

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Expected KEY=VALUE");
    }

    // ---- validation: memory ----

    @Test
    void execute_memoryTooLow_sendsError() {
        RunContainerRequest req = validRequest();
        req.setMemoryMb(2L);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "at least 4 MB");
    }

    @Test
    void execute_memoryTooHigh_sendsError() {
        RunContainerRequest req = validRequest();
        req.setMemoryMb(100_000L);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "must not exceed");
    }

    @Test
    void execute_memoryExceedsCustomMax_sendsError() throws Exception {
        java.lang.reflect.Field maxMbField = RunContainerUseCase.class.getDeclaredField("memoryLimitMaxMb");
        maxMbField.setAccessible(true);
        maxMbField.setLong(useCase, 1536L);

        RunContainerRequest req = validRequest();
        req.setMemoryMb(2000L);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "1536");
    }

    @Test
    void execute_memoryExceedsPerRepoMax_sendsError() {
        when(config.getOptionalValue("repository.memory-limit.max-mb." + REPO, Long.class))
                .thenReturn(java.util.Optional.of(1024L));

        RunContainerRequest req = validRequest();
        req.setMemoryMb(1500L);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "1024");
    }

    @Test
    void execute_memoryWithinPerRepoMax_passes() {
        stubHappyPath();
        when(config.getOptionalValue("repository.memory-limit.max-mb." + REPO, Long.class))
                .thenReturn(java.util.Optional.of(2048L));

        RunContainerRequest req = validRequest();
        req.setMemoryMb(1500L);

        useCase.execute(req, events::add);

        assertTrue(events.stream().noneMatch(e -> e.getType() == ContainerEvent.EventType.ERROR
                && e.getMessage().contains("exceed")));
    }

    // ---- validation: database name ----

    @Test
    void execute_invalidDatabaseName_sendsError() {
        RunContainerRequest req = validRequest();
        req.setDatabaseName("123bad");

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Invalid database name");
    }

    // ---- validation: dump ID ----

    @Test
    void execute_invalidDumpId_sendsError() {
        RunContainerRequest req = validRequest();
        req.setDumpId("not-a-uuid");

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Invalid UUID format");
    }

    // ---- validation: operations password ----

    @Test
    void execute_deleteDatabaseWithoutPassword_sendsError() {
        when(dumpStorageService.validateOperationsPassword(null)).thenReturn(false);
        RunContainerRequest req = validRequest();
        req.setDeleteDatabaseOnExpiration(true);
        req.setOperationsPasswordValidated(false);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Invalid operations password");
    }

    @Test
    void execute_deleteDatabaseWithoutPassword_passwordNotRequired_succeeds() {
        when(dumpStorageService.validateOperationsPassword(null)).thenReturn(true);
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setDeleteDatabaseOnExpiration(true);
        req.setOperationsPasswordValidated(false);

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    // ---- whitelist ----

    @Test
    void execute_emptyAllowedRepositories_sendsError() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(Collections.emptyList());

        useCase.execute(validRequest(), events::add);

        assertLastEventError("Validating", "No repositories are allowed");
    }

    @Test
    void execute_repositoryNotInWhitelist_sendsError() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("other-repo"));

        useCase.execute(validRequest(), events::add);

        assertLastEventError("Validating", "not in the allowed list");
    }

    // ---- pull failure ----

    @Test
    void execute_pullFails_sendsError() throws Exception {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);

        doThrow(new RuntimeException("connection refused"))
                .when(dockerContainerPort).pullImage(eq(IMAGE_REF), eq(REPO), eq(TAG), any());

        useCase.execute(validRequest(), events::add);

        assertLastEventError("Pulling", "Failed to pull image");
    }

    // ---- create failure ----

    @Test
    void execute_createContainerFails_sendsError() throws Exception {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);
        stubPullSuccess();

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(IMAGE_REF)).thenReturn(createCmd);
        when(portFinder.getContainerPorts(REPO)).thenReturn(Collections.emptyList());
        when(config.getOptionalValue("repository.hidden-env." + REPO, String.class))
                .thenReturn(Optional.empty());
        when(createCmd.exec()).thenThrow(new RuntimeException("name conflict"));

        useCase.execute(validRequest(), events::add);

        assertLastEventError("Creating", "Failed to create container");
    }

    // ---- start failure ----

    @Test
    void execute_startFails_sendsError() {
        stubHappyPath();
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);
        when(startCmd.exec()).thenThrow(new RuntimeException("cannot start"));

        useCase.execute(validRequest(), events::add);

        assertHasError("Starting", "Failed to start container");
    }

    // ---- happy path ----

    @Test
    void execute_happyPath_sendsSuccessEvent() {
        stubHappyPath();

        useCase.execute(validRequest(), events::add);

        assertTrue(hasEvent(EventType.SUCCESS), "Expected SUCCESS event");
        assertFalse(hasEvent(EventType.ERROR), "Expected no ERROR events");
        assertEquals("Complete", lastEvent().getStep());
        assertTrue(lastEvent().getMessage().contains("Container started successfully"));
        verify(resourceCounterService).increment(ResourceCounterService.CONTAINERS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_happyPath_labelsContainerWithCreator() {
        stubHappyPath();
        when(actorResolver.usernameOrSystem()).thenReturn("alice");

        useCase.execute(validRequest(), events::add);

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(dockerClient.createContainerCmd(IMAGE_REF)).withLabels(captor.capture());
        assertEquals("alice", captor.getValue().get(br.com.fzdevx.domain.shared.Constants.CREATED_BY_LABEL));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_happyPath_noIdentity_skipsCreatorLabel() {
        stubHappyPath();
        when(actorResolver.usernameOrSystem()).thenReturn(null); // legacy password mode

        useCase.execute(validRequest(), events::add);

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(dockerClient.createContainerCmd(IMAGE_REF)).withLabels(captor.capture());
        assertFalse(captor.getValue().containsKey(br.com.fzdevx.domain.shared.Constants.CREATED_BY_LABEL));
    }

    @Test
    void execute_happyPathWithTicket_cleansUpTicket() {
        stubHappyPath();
        String ticket = "test-ticket";

        useCase.execute(validRequest(), events::add, ticket);

        assertTrue(hasEvent(EventType.SUCCESS));
        assertFalse(useCase.cancel(ticket), "Ticket should be removed after execution");
    }

    @Test
    void execute_happyPathWithValidEnvVars_succeeds() {
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setEnvVars(List.of("MY_VAR=value", "OTHER_VAR=123"));

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    @Test
    void execute_happyPathWithValidMemory_succeeds() {
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setMemoryMb(512L);

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    // ---- java-opts merge ----

    @Test
    void execute_javaOptsVar_mergesWithExistingHiddenEnv() {
        stubHappyPath();
        when(config.getOptionalValue("repository.hidden-env." + REPO, String.class))
                .thenReturn(Optional.of("JAVA_OPTS=-server -Xms512m -Xmx1300m -XX:MetaspaceSize=512m -Djava.awt.headless=true"));
        when(config.getOptionalValue("repository.java-opts-var." + REPO, String.class))
                .thenReturn(Optional.of("JAVA_OPTS"));

        RunContainerRequest req = validRequest();
        req.setMemoryMb(1536L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> envCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(IMAGE_REF)).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(mock(com.github.dockerjava.api.command.CreateContainerResponse.class));
        when(createCmd.exec().getId()).thenReturn(CONTAINER_ID);
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        useCase.execute(req, events::add);

        verify(createCmd).withEnv(envCaptor.capture());
        List<String> envList = envCaptor.getValue();
        String javaOpts = envList.stream().filter(e -> e.startsWith("JAVA_OPTS=")).findFirst().orElse("");
        assertTrue(javaOpts.contains("-Xmx1152m"), "Should have new Xmx: " + javaOpts);
        assertTrue(javaOpts.contains("-Xms384m"), "Should have new Xms: " + javaOpts);
        assertTrue(javaOpts.contains("-XX:MetaspaceSize=512m"), "Should preserve MetaspaceSize: " + javaOpts);
        assertTrue(javaOpts.contains("-Djava.awt.headless=true"), "Should preserve system properties: " + javaOpts);
        assertTrue(javaOpts.contains("-server"), "Should preserve -server: " + javaOpts);
        assertFalse(javaOpts.contains("-Xmx1300m"), "Should NOT have old Xmx: " + javaOpts);
        assertFalse(javaOpts.contains("-Xms512m"), "Should NOT have old Xms: " + javaOpts);
    }

    @Test
    void execute_javaOptsVar_setsNewValue_whenNoExistingOpts() {
        stubHappyPath();
        when(config.getOptionalValue("repository.java-opts-var." + REPO, String.class))
                .thenReturn(Optional.of("JAVA_OPTS"));

        RunContainerRequest req = validRequest();
        req.setMemoryMb(512L);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> envCaptor = org.mockito.ArgumentCaptor.forClass(List.class);

        useCase.execute(req, events::add);

        verify(dockerClient.createContainerCmd(IMAGE_REF)).withEnv(envCaptor.capture());
        List<String> envList = envCaptor.getValue();
        String javaOpts = envList.stream().filter(e -> e.startsWith("JAVA_OPTS=")).findFirst().orElse("");
        assertTrue(javaOpts.contains("-Xmx384m"), "Should have Xmx: " + javaOpts);
        assertTrue(javaOpts.contains("-Xms128m"), "Should have Xms: " + javaOpts);
    }

    @Test
    void execute_nullContainerName_succeeds() {
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setContainerName(null);

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    @Test
    void execute_blankContainerName_succeeds() {
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setContainerName("   ");

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    @Test
    void execute_deleteDatabaseWithValidPassword_succeeds() {
        stubHappyPath();
        RunContainerRequest req = validRequest();
        req.setDeleteDatabaseOnExpiration(true);
        req.setOperationsPasswordValidated(true);

        useCase.execute(req, events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
    }

    // ---- expiration recalculation ----

    @Test
    void resolveExpiration_inFuture_returnsOriginalTimestamp() {
        RunContainerRequest req = validRequest();
        LocalDateTime futureTime = LocalDateTime.now().plusMinutes(30);
        req.setExpiresAt(futureTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Instant result = useCase.resolveExpiration(req, Instant.now());

        Instant expected = futureTime.atZone(ZoneId.systemDefault()).toInstant();
        assertTrue(Duration.between(expected, result).abs().toSeconds() < 2);
    }

    @Test
    void resolveExpiration_inPast_recalculatesFromNow() {
        // Simulate: user submitted 10 minutes ago with 5-minute expiration
        // startedAt = 10 minutes ago, expiresAt = 5 minutes ago (was 5 min after submission)
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(10));
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.now().minus(Duration.ofMinutes(5)), ZoneId.systemDefault());

        RunContainerRequest req = validRequest();
        req.setExpiresAt(expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        Instant result = useCase.resolveExpiration(req, startedAt);

        // Original duration was 5 minutes. Should be recalculated to ~5 minutes from now.
        assertTrue(result.isAfter(Instant.now()), "Recalculated expiration should be in the future");
        long minutesFromNow = Duration.between(Instant.now(), result).toMinutes();
        assertTrue(minutesFromNow >= 4 && minutesFromNow <= 6,
                "Expected ~5 minutes from now but got " + minutesFromNow);
    }

    @Test
    void resolveExpiration_nullExpiresAt_returnsNull() {
        RunContainerRequest req = validRequest();
        req.setExpiresAt(null);

        Instant result = useCase.resolveExpiration(req, Instant.now());

        assertNull(result);
    }

    @Test
    void resolveExpiration_blankExpiresAt_returnsNull() {
        RunContainerRequest req = validRequest();
        req.setExpiresAt("  ");

        Instant result = useCase.resolveExpiration(req, Instant.now());

        assertNull(result);
    }

    // ---- cancellation ----

    @Test
    void cancel_unknownTicket_returnsFalse() {
        assertFalse(useCase.cancel("unknown-ticket"));
    }

    // ---- interaction verification ----

    @Test
    void execute_noDockerInteractionOnValidationFailure() {
        RunContainerRequest req = validRequest();
        req.setRepository(null);

        useCase.execute(req, events::add);

        verifyNoInteractions(dockerClient);
        verifyNoInteractions(registryService);
        verifyNoInteractions(portFinder);
    }

    @Test
    void execute_noDockerInteractionOnWhitelistFailure() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("other-repo"));

        useCase.execute(validRequest(), events::add);

        verifyNoInteractions(dockerClient);
    }

    // ---- port release on all exit paths ----

    private void stubHappyPathWithPorts() {
        stubHappyPath();
        when(portFinder.getContainerPorts(REPO)).thenReturn(List.of(8080));
        when(portFinder.getHostPortStart(REPO)).thenReturn(10000);
        when(portFinder.findAvailablePorts(1, 10000)).thenReturn(List.of(10000));
    }

    @Test
    void execute_withPortMapping_releasesPortsOnSuccess() {
        stubHappyPathWithPorts();

        useCase.execute(validRequest(), events::add);

        assertTrue(hasEvent(EventType.SUCCESS));
        verify(portFinder).releasePorts(List.of(10000));
    }

    @Test
    void execute_withPortMapping_releasesPortsOnStartFailure() {
        stubHappyPathWithPorts();
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);
        when(startCmd.exec()).thenThrow(new RuntimeException("port already in use"));

        useCase.execute(validRequest(), events::add);

        assertHasError("Starting", "Port binding conflict");
        verify(portFinder).releasePorts(List.of(10000));
    }
}
