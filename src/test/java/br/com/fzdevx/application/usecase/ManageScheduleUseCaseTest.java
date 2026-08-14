package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CreateScheduleRequest;
import br.com.fzdevx.application.dto.UpdateScheduleRequest;
import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.ContainerSchedule;
import br.com.fzdevx.domain.model.ScheduleAction;
import br.com.fzdevx.domain.model.ScheduleType;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManageScheduleUseCaseTest {

    private static final String VALID_CONTAINER_ID = "abc123def456";
    private static final String VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock ScheduleRepository scheduleRepository;
    @Mock ContainerExpirationService expirationService;
    @Mock ContainerProtectionService protectionService;
    @Mock ContainerSchedulingService schedulingService;
    @Mock br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;
    @Mock br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

    @InjectMocks
    ManageScheduleUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void wireTenantVisibility() {
        useCase.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
        useCase.tenantSharing = br.com.fzdevx.infrastructure.config.TestTenantSharing.withoutRepository();
        // mirror the real atomic update: apply the mutator to whatever findById is stubbed with
        org.mockito.Mockito.lenient()
                .when(scheduleRepository.update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    var stored = scheduleRepository.findById(invocation.getArgument(0));
                    if (stored.isEmpty()) {
                        return false;
                    }
                    java.util.function.Consumer<ContainerSchedule> mutator = invocation.getArgument(1);
                    mutator.accept(stored.get());
                    return true;
                });
    }

    private CreateScheduleRequest validOneTimeStopRequest() {
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Stop nightly");
        req.setAction("STOP");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        req.setContainerId(VALID_CONTAINER_ID);
        return req;
    }

    private CreateScheduleRequest validRecurringStopRequest() {
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Stop nightly");
        req.setAction("STOP");
        req.setScheduleType("RECURRING");
        req.setCronExpression("0 3 * * *"); // daily at 3 AM
        req.setContainerId(VALID_CONTAINER_ID);
        return req;
    }

    // ---- create: validation ----

    @Test
    void create_invalidName_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setName("-bad");
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_invalidAction_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setAction("INVALID");
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_invalidScheduleType_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setScheduleType("INVALID");
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_oneTime_noScheduledAt_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setScheduledAt(null);
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_oneTime_pastScheduledAt_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setScheduledAt(Instant.now().minus(1, ChronoUnit.HOURS).toString());
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_recurring_invalidCron_throws() {
        CreateScheduleRequest req = validRecurringStopRequest();
        req.setCronExpression("invalid");
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_stopWithoutContainerId_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setContainerId(null);
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_stopWithInvalidContainerId_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setContainerId("INVALID!");
        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    // ---- create: conflict detection ----

    @Test
    void create_duplicateAction_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID))
                .thenReturn(List.of(new ContainerSchedule("existing", ScheduleAction.STOP, ScheduleType.ONE_TIME)));

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_removeWhenOtherSchedulesExist_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setAction("REMOVE");
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID))
                .thenReturn(List.of(new ContainerSchedule("existing", ScheduleAction.STOP, ScheduleType.ONE_TIME)));

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void create_anyActionWhenRemoveExists_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID))
                .thenReturn(List.of(new ContainerSchedule("kill", ScheduleAction.REMOVE, ScheduleType.ONE_TIME)));

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    // ---- create: protected container ----

    @Test
    void create_stopOnProtectedContainer_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        when(protectionService.isProtectedContainer(VALID_CONTAINER_ID)).thenReturn(true);

        InvalidInputException ex = assertThrows(InvalidInputException.class, () -> useCase.create(req));
        assertTrue(ex.getMessage().contains("protected"));
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void create_removeOnProtectedContainer_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setAction("REMOVE");
        when(protectionService.isProtectedContainer(VALID_CONTAINER_ID)).thenReturn(true);

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void create_startOnProtectedContainer_allowed() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        req.setAction("START");
        when(protectionService.isProtectedContainer(VALID_CONTAINER_ID)).thenReturn(true);
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID)).thenReturn(Collections.emptyList());

        ContainerSchedule result = useCase.create(req);

        assertEquals(ScheduleAction.START, result.getAction());
        verify(scheduleRepository).save(result);
    }

    // ---- create: expiration conflict ----

    @Test
    void create_oneTimeAfterExpiration_throws() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        when(expirationService.getExpiresAt(VALID_CONTAINER_ID.substring(0, 10))).thenReturn(expiresAt);
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID)).thenReturn(Collections.emptyList());

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    // ---- create: happy paths ----

    @Test
    void create_validOneTimeStop_savesAndReturns() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID)).thenReturn(Collections.emptyList());

        ContainerSchedule result = useCase.create(req);

        assertNotNull(result.getId());
        assertEquals("Stop nightly", result.getName());
        assertEquals(ScheduleAction.STOP, result.getAction());
        assertEquals(ScheduleType.ONE_TIME, result.getScheduleType());
        assertTrue(result.isEnabled());
        assertEquals(VALID_CONTAINER_ID, result.getContainerId());
        assertNotNull(result.getNextExecutionAt());
        verify(scheduleRepository).save(result);
    }

    @Test
    void create_validRecurring_setsNextExecution() {
        CreateScheduleRequest req = validRecurringStopRequest();
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID)).thenReturn(Collections.emptyList());

        ContainerSchedule result = useCase.create(req);

        assertEquals(ScheduleType.RECURRING, result.getScheduleType());
        assertEquals("0 3 * * *", result.getCronExpression());
        assertNotNull(result.getNextExecutionAt());
    }

    @Test
    void createAndSchedule_schedulesAfterCreate() {
        CreateScheduleRequest req = validOneTimeStopRequest();
        when(scheduleRepository.findByContainerId(VALID_CONTAINER_ID)).thenReturn(Collections.emptyList());

        ContainerSchedule result = useCase.createAndSchedule(req);

        verify(schedulingService).scheduleNext(result);
    }

    // ---- create: CREATE action ----

    @Test
    void create_createActionWithoutConfig_throws() {
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        req.setCreateConfig(null);

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    private CreateScheduleRequest oneTimeCreateArmingDeletion() {
        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        var config = new br.com.fzdevx.application.dto.RunContainerRequest();
        config.setRepository("myapp");
        config.setDatabaseName("mydb");
        config.setDeleteDatabaseOnExpiration(true);
        req.setCreateConfig(config);
        return req;
    }

    private br.com.fzdevx.application.port.ManagedDatabaseRepository databaseMetadata;

    private void wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission... permissions) {
        var user = new br.com.fzdevx.infrastructure.config.CurrentUser();
        user.set("u1", "alice", java.util.Set.of(permissions));
        databaseMetadata =
                org.mockito.Mockito.mock(br.com.fzdevx.application.port.ManagedDatabaseRepository.class);
        useCase.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(user, databaseMetadata);
    }

    @Test
    void create_oneTimeCreate_armingDeletion_withoutDeleteGrant_denied() {
        wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission.SCHEDULES_MANAGE);

        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.create(oneTimeCreateArmingDeletion()));
        verify(scheduleRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void create_oneTimeCreate_armingDeletion_deleteOwn_foreignDatabase_denied() {
        wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN);
        var foreign = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        foreign.setCreatedBy("bob");
        when(databaseMetadata.find("myapp", "mydb")).thenReturn(Optional.of(foreign));

        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.create(oneTimeCreateArmingDeletion()));
    }

    @Test
    void create_oneTimeCreate_armingDeletion_deleteOwn_ownDatabase_saves() {
        wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN);
        var mine = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        mine.setCreatedBy("alice");
        when(databaseMetadata.find("myapp", "mydb")).thenReturn(Optional.of(mine));

        ContainerSchedule schedule = useCase.create(oneTimeCreateArmingDeletion());

        assertTrue(schedule.getCreateConfig().isDeleteDatabaseOnExpiration());
        verify(scheduleRepository).save(any());
    }

    @Test
    void create_oneTimeCreate_armingDeletion_withoutDatabase_throws() {
        wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE);
        CreateScheduleRequest req = oneTimeCreateArmingDeletion();
        req.getCreateConfig().setDatabaseName(null);

        assertThrows(InvalidInputException.class, () -> useCase.create(req));
    }

    @Test
    void update_armingDeletion_withoutDeleteGrant_denied() {
        wireDeletionPolicy(br.com.fzdevx.domain.model.auth.Permission.SCHEDULES_MANAGE);
        ContainerSchedule stored = new ContainerSchedule("auto", ScheduleAction.CREATE, ScheduleType.ONE_TIME);
        stored.setId(VALID_UUID);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(stored));

        UpdateScheduleRequest request = new UpdateScheduleRequest();
        var config = new br.com.fzdevx.application.dto.RunContainerRequest();
        config.setRepository("myapp");
        config.setDatabaseName("mydb");
        config.setDeleteDatabaseOnExpiration(true);
        request.setCreateConfig(config);

        assertThrows(br.com.fzdevx.domain.exception.AccessDeniedException.class,
                () -> useCase.update(VALID_UUID, request));
    }

    @Test
    void create_targetingForeignTenantContainer_denied() {
        // schedules fire outside any request scope where no tenant check can run,
        // so the target container must be guarded at create time
        org.mockito.Mockito.doThrow(new EntityNotFoundException("Container not found."))
                .when(containerTenantGuard).requireVisible(VALID_CONTAINER_ID);

        assertThrows(EntityNotFoundException.class, () -> useCase.create(validOneTimeStopRequest()));
        verify(scheduleRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void update_repointingToForeignTenantContainer_denied() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        s.setId(VALID_UUID);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        org.mockito.Mockito.doThrow(new EntityNotFoundException("Container not found."))
                .when(containerTenantGuard).requireVisible("feedfacecafe");

        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setContainerId("feedfacecafe");
        assertThrows(EntityNotFoundException.class, () -> useCase.update(VALID_UUID, request));
    }

    @Test
    void create_createAction_copiesTenantIntoPersistedConfig() {
        // scheduled creates run outside a request scope, so the container's
        // tenant must travel with the schedule's config, not the actor
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(),
                new java.util.LinkedHashSet<>(java.util.List.of("tenant-1")));
        useCase.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(rbacUser, null);

        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        req.setCreateConfig(new br.com.fzdevx.application.dto.RunContainerRequest());

        ContainerSchedule schedule = useCase.create(req);

        assertEquals("tenant-1", schedule.getTenantId());
        assertEquals("tenant-1", schedule.getCreateConfig().getTenantId());
    }

    @Test
    void create_createAction_copiesSharingIntoPersistedConfig() {
        // a schedule shared with tenant B must produce containers tenant B can
        // see - the config is what executeCreate() reads at fire time
        var tenantRepository = mock(br.com.fzdevx.application.port.TenantRepository.class);
        var tenantTwo = new br.com.fzdevx.domain.model.auth.Tenant("Two", null);
        tenantTwo.setId("tenant-2");
        when(tenantRepository.findAll()).thenReturn(java.util.List.of(tenantTwo));
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(),
                new java.util.LinkedHashSet<>(java.util.List.of("tenant-1")));
        useCase.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(rbacUser, null);
        useCase.tenantSharing = br.com.fzdevx.infrastructure.config.TestTenantSharing.with(tenantRepository);

        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        req.setSharedWithTenants(java.util.List.of("tenant-2"));
        req.setCreateConfig(new br.com.fzdevx.application.dto.RunContainerRequest());

        ContainerSchedule schedule = useCase.create(req);

        assertEquals(java.util.List.of("tenant-2"), schedule.getSharedWithTenants());
        assertEquals(java.util.List.of("tenant-2"), schedule.getCreateConfig().getSharedWithTenants());
    }

    @Test
    void create_createAction_discardsSharingSmuggledInsideTheConfig() {
        // the config deserializes a sharedWithTenants field of its own; left
        // alone it would reach the docker label without the unknown-tenant
        // check, granting a not-yet-existing tenant access to the container
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(),
                new java.util.LinkedHashSet<>(java.util.List.of("tenant-1")));
        useCase.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(rbacUser, null);

        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        var config = new br.com.fzdevx.application.dto.RunContainerRequest();
        config.setSharedWithTenants(java.util.List.of("planted-tenant-id"));
        req.setCreateConfig(config);

        ContainerSchedule schedule = useCase.create(req);

        assertTrue(schedule.getCreateConfig().getSharedWithTenants().isEmpty());
    }

    // ---- updateSharing ----

    @Test
    void updateSharing_replacesSharesAndSyncsTheCreateConfig() {
        var tenantB = new br.com.fzdevx.domain.model.auth.Tenant("B", null);
        tenantB.setId("tenant-b");
        var tenantRepository = mock(br.com.fzdevx.application.port.TenantRepository.class);
        when(tenantRepository.findAll()).thenReturn(java.util.List.of(tenantB));
        useCase.tenantSharing = br.com.fzdevx.infrastructure.config.TestTenantSharing.with(tenantRepository);

        ContainerSchedule stored = new ContainerSchedule("auto", ScheduleAction.CREATE, ScheduleType.ONE_TIME);
        stored.setId(VALID_UUID);
        stored.setTenantId("tenant-a");
        stored.setCreateConfig(new br.com.fzdevx.domain.model.RunContainerConfig());
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(stored));

        ContainerSchedule updated = useCase.updateSharing(VALID_UUID,
                java.util.List.of("tenant-a", "tenant-b"), null, false);

        // owner stripped, config kept in sync - it is what a scheduled CREATE
        // reads at fire time, outside any request scope
        assertEquals(java.util.List.of("tenant-b"), updated.getSharedWithTenants());
        assertEquals(java.util.List.of("tenant-b"), updated.getCreateConfig().getSharedWithTenants());
        assertEquals("tenant-a", updated.getCreateConfig().getTenantId());
    }

    @Test
    void updateSharing_unknownTenant_throws() {
        var tenantRepository = mock(br.com.fzdevx.application.port.TenantRepository.class);
        when(tenantRepository.findAll()).thenReturn(java.util.List.of());
        useCase.tenantSharing = br.com.fzdevx.infrastructure.config.TestTenantSharing.with(tenantRepository);

        ContainerSchedule stored = new ContainerSchedule("auto", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        stored.setId(VALID_UUID);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(stored));

        assertThrows(InvalidInputException.class,
                () -> useCase.updateSharing(VALID_UUID, java.util.List.of("nope"), null, false));
    }

    @Test
    void updateSharing_notFound_throws() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class,
                () -> useCase.updateSharing(VALID_UUID, java.util.List.of(), null, false));
    }

    @Test
    void persistedConfigNeverAliasesTheSchedulesLiveList() {
        // an aliased reference would let a later in-place mutation of the
        // schedule's list rewrite the container-label list past validation
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(),
                new java.util.LinkedHashSet<>(java.util.List.of("tenant-1")));
        useCase.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(rbacUser, null);

        CreateScheduleRequest req = new CreateScheduleRequest();
        req.setName("Auto create");
        req.setAction("CREATE");
        req.setScheduleType("ONE_TIME");
        req.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS).toString());
        req.setCreateConfig(new br.com.fzdevx.application.dto.RunContainerRequest());

        ContainerSchedule schedule = useCase.create(req);
        schedule.getSharedWithTenants().add("smuggled-later");

        assertFalse(schedule.getCreateConfig().getSharedWithTenants().contains("smuggled-later"));
    }

    // ---- toggleEnabled ----

    @Test
    void toggleEnabled_notFound_throws() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> useCase.toggleEnabled(VALID_UUID));
    }

    @Test
    void toggleEnabled_enabledToDisabled() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        s.setId(VALID_UUID);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));

        ContainerSchedule result = useCase.toggleEnabled(VALID_UUID);

        assertFalse(result.isEnabled());
        // atomic mutation - a full save could clobber concurrent execution writes
        verify(scheduleRepository).update(eq(VALID_UUID), any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void toggleEnabled_disabledToEnabled() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.RECURRING);
        s.setId(VALID_UUID);
        s.setEnabled(false);
        s.setCronExpression("0 3 * * *");
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));

        ContainerSchedule result = useCase.toggleEnabled(VALID_UUID);

        assertTrue(result.isEnabled());
        assertNotNull(result.getNextExecutionAt());
    }

    @Test
    void toggleEnabled_reEnableExecutedOneTime_throws() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        s.setId(VALID_UUID);
        s.setEnabled(false);
        s.setLastExecutedAt(Instant.now());
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));

        assertThrows(InvalidInputException.class, () -> useCase.toggleEnabled(VALID_UUID));
    }

    @Test
    void toggleAndReschedule_enabled_schedulesNext() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.RECURRING);
        s.setId(VALID_UUID);
        s.setEnabled(false);
        s.setCronExpression("0 3 * * *");
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));

        useCase.toggleAndReschedule(VALID_UUID);

        verify(schedulingService).scheduleNext(any());
    }

    @Test
    void toggleAndReschedule_disabled_cancels() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        s.setId(VALID_UUID);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));

        useCase.toggleAndReschedule(VALID_UUID);

        verify(schedulingService).cancel(VALID_UUID);
    }

    // ---- delete ----

    @Test
    void delete_notFound_throws() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> useCase.delete(VALID_UUID));
    }

    @Test
    void delete_found_deletes() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(new ContainerSchedule()));
        useCase.delete(VALID_UUID);
        verify(scheduleRepository).delete(VALID_UUID);
    }

    @Test
    void deleteAndCancel_cancelsFirst() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(new ContainerSchedule()));
        useCase.deleteAndCancel(VALID_UUID);
        var inOrder = inOrder(schedulingService, scheduleRepository);
        inOrder.verify(schedulingService).cancel(VALID_UUID);
        inOrder.verify(scheduleRepository).delete(VALID_UUID);
    }

    // ---- executeNow ----

    @Test
    void executeNow_notFound_throws() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> useCase.executeNow(VALID_UUID));
    }

    @Test
    void executeNow_disabled_throws() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        s.setEnabled(false);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        assertThrows(InvalidInputException.class, () -> useCase.executeNow(VALID_UUID));
    }

    @Test
    void executeNow_valid_delegates() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        useCase.executeNow(VALID_UUID);
        verify(schedulingService).executeNow(VALID_UUID);
    }

    // ---- update ----

    @Test
    void update_notFound_throws() {
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class,
                () -> useCase.update(VALID_UUID, new UpdateScheduleRequest()));
    }

    @Test
    void update_invalidName_throws() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        UpdateScheduleRequest req = new UpdateScheduleRequest();
        req.setName("-bad");
        assertThrows(InvalidInputException.class, () -> useCase.update(VALID_UUID, req));
    }

    @Test
    void update_validName_updatesAndSaves() {
        ContainerSchedule s = new ContainerSchedule("old", ScheduleAction.STOP, ScheduleType.ONE_TIME);
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        UpdateScheduleRequest req = new UpdateScheduleRequest();
        req.setName("new name");

        ContainerSchedule result = useCase.update(VALID_UUID, req);

        assertEquals("new name", result.getName());
        // atomic mutation - a full save could clobber concurrent execution writes
        verify(scheduleRepository).update(eq(VALID_UUID), any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void updateAndReschedule_enabled_reschedules() {
        ContainerSchedule s = new ContainerSchedule("test", ScheduleAction.STOP, ScheduleType.RECURRING);
        s.setCronExpression("0 3 * * *");
        when(scheduleRepository.findById(VALID_UUID)).thenReturn(Optional.of(s));
        UpdateScheduleRequest req = new UpdateScheduleRequest();

        useCase.updateAndReschedule(VALID_UUID, req);

        verify(schedulingService).cancel(VALID_UUID);
        verify(schedulingService).scheduleNext(any());
    }

    // ---- findAll / findByContainerId ----

    @Test
    void findAll_delegates() {
        when(scheduleRepository.findAll()).thenReturn(List.of(new ContainerSchedule()));
        assertEquals(1, useCase.findAll().size());
    }

    @Test
    void findByContainerId_delegates() {
        when(scheduleRepository.findByContainerId("abc")).thenReturn(List.of(new ContainerSchedule()));
        assertEquals(1, useCase.findByContainerId("abc").size());
    }
}
