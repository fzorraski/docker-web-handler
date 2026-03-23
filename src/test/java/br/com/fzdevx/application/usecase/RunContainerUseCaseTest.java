package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerEvent.EventType;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.docker.PortFinder;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
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

    @InjectMocks
    RunContainerUseCase useCase;

    private List<ContainerEvent> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
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
     * Stubs pull to immediately complete. Must send at least one PullResponseItem
     * via onNext before onComplete, because PullImageResultCallback.throwFirstError()
     * throws "Could not pull image" when latestItem is null.
     */
    private void stubPullSuccess() {
        PullImageCmd pullCmd = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd(IMAGE_REF)).thenReturn(pullCmd);
        when(pullCmd.exec(any())).thenAnswer(invocation -> {
            PullImageResultCallback cb = invocation.getArgument(0);
            PullResponseItem item = mock(PullResponseItem.class);
            when(item.isPullSuccessIndicated()).thenReturn(true);
            when(item.getStatus()).thenReturn("Pull complete");
            cb.onNext(item);
            cb.onComplete();
            return cb;
        });
    }

    private void stubHappyPath() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);
        when(registryService.buildAuthConfig(REPO, TAG)).thenReturn(null);

        stubPullSuccess();

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
        RunContainerRequest req = validRequest();
        req.setDeleteDatabaseOnExpiration(true);
        req.setOperationsPasswordValidated(false);

        useCase.execute(req, events::add);

        assertLastEventError("Validating", "Invalid operations password");
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
    void execute_pullFails_sendsError() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);
        when(registryService.buildAuthConfig(REPO, TAG)).thenReturn(null);

        PullImageCmd pullCmd = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd(IMAGE_REF)).thenReturn(pullCmd);
        when(pullCmd.exec(any())).thenThrow(new RuntimeException("connection refused"));

        useCase.execute(validRequest(), events::add);

        assertLastEventError("Pulling", "Failed to pull image");
    }

    // ---- create failure ----

    @Test
    void execute_createContainerFails_sendsError() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of(REPO));
        when(registryService.buildFullImageRef(REPO, TAG)).thenReturn(IMAGE_REF);
        when(registryService.buildAuthConfig(REPO, TAG)).thenReturn(null);
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

        assertLastEventError("Starting", "Failed to start container");
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
}
