package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.RemoveContainerRequest;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.usecase.RemoveContainerUseCase;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.application.usecase.RunMigrationUseCase;
import br.com.fzdevx.application.usecase.StreamContainerLogsUseCase;
import br.com.fzdevx.application.usecase.StreamContainerStatsUseCase;
import br.com.fzdevx.application.usecase.UpgradeContainerUseCase;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerSseControllerTest {

    @Mock RequestStash requestStash;
    @Mock RunContainerUseCase runContainerUseCase;
    @Mock RemoveContainerUseCase removeContainerUseCase;
    @Mock RunMigrationUseCase runMigrationUseCase;
    @Mock UpgradeContainerUseCase upgradeContainerUseCase;
    @Mock DumpStorageService dumpStorageService;
    @Mock StreamContainerLogsUseCase streamContainerLogsUseCase;
    @Mock StreamContainerStatsUseCase streamContainerStatsUseCase;
    @Mock WebhookService webhookService;
    @Mock ContainerListBroadcaster broadcaster;
    @Mock br.com.fzdevx.application.port.ManagedDatabaseRepository managedDatabaseRepository;
    // prepareRun/prepareRemove both check the database entitlement; a mock no-ops
    @Mock br.com.fzdevx.infrastructure.config.TenantEntitlements tenantEntitlements;
    @Mock br.com.fzdevx.infrastructure.persistence.SnapshotStorageService snapshotStorageService;

    @InjectMocks
    ContainerSseController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy.passthrough();
        controller.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
        controller.tenantSharing = br.com.fzdevx.infrastructure.config.TestTenantSharing.withoutRepository();
        controller.containerTenantGuard = br.com.fzdevx.infrastructure.docker.TestContainerTenantGuard.passthrough();
    }

    // ---- prepareRemove ----

    @org.junit.jupiter.api.Test
    void prepareRemove_withDeleteDatabase_withoutDatabaseDeletePermission_returns403() {
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(
                br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN));
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(rbacUser, managedDatabaseRepository);

        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myapp");
        req.setDatabaseName("mydb");

        Response res = controller.prepareRemove(req);

        assertEquals(403, res.getStatus());
    }

    @org.junit.jupiter.api.Test
    void prepareRemove_deleteOwn_ownDatabase_passes() {
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(
                br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN));
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(rbacUser, managedDatabaseRepository);
        var mine = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        mine.setCreatedBy("alice");
        when(managedDatabaseRepository.find("myapp", "mydb")).thenReturn(java.util.Optional.of(mine));
        when(dumpStorageService.validateOperationsPassword(any())).thenReturn(true);
        when(requestStash.stashRemove(any())).thenReturn("ticket-own");

        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myapp");
        req.setDatabaseName("mydb");
        req.setOperationsPassword("secret");

        assertEquals(200, controller.prepareRemove(req).getStatus());
    }

    @org.junit.jupiter.api.Test
    void prepareRemove_deleteOwn_foreignDatabase_returns403() {
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "alice", java.util.Set.of(
                br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN));
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(rbacUser, managedDatabaseRepository);
        var foreign = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        foreign.setCreatedBy("bob");
        when(managedDatabaseRepository.find("myapp", "mydb")).thenReturn(java.util.Optional.of(foreign));

        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myapp");
        req.setDatabaseName("mydb");

        assertEquals(403, controller.prepareRemove(req).getStatus());
    }

    // ---- prepareRun: arming database deletion ----

    private RunContainerRequest runRequestArmingDeletion() {
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository("myapp");
        req.setTag("1.0");
        req.setContainerName("c1");
        req.setDatabaseName("mydb");
        req.setDeleteDatabaseOnExpiration(true);
        return req;
    }

    private br.com.fzdevx.infrastructure.config.CurrentUser userWith(
            br.com.fzdevx.domain.model.auth.Permission... permissions) {
        var user = new br.com.fzdevx.infrastructure.config.CurrentUser();
        user.set("u1", "alice", java.util.Set.of(permissions));
        return user;
    }

    @org.junit.jupiter.api.Test
    void prepareRun_stripsAClientSuppliedOnBehalfOf() {
        // scheduler-only attribution field: a client posting it must not spoof
        // who armed a deferred database deletion
        when(requestStash.stash(any())).thenReturn("t");
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository("myapp");
        req.setTag("1.0");
        req.setContainerName("c1");
        req.setOnBehalfOf("victim-admin");

        controller.prepareRun(req);

        org.mockito.ArgumentCaptor<RunContainerRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RunContainerRequest.class);
        verify(requestStash).stash(captor.capture());
        assertNull(captor.getValue().getOnBehalfOf());
    }

    @org.junit.jupiter.api.Test
    void prepareRun_armingDeletion_withoutDeleteGrant_returns403AndNeverStashes() {
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(userWith(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN),
                        managedDatabaseRepository);

        Response res = controller.prepareRun(runRequestArmingDeletion());

        assertEquals(403, res.getStatus());
        verify(requestStash, never()).stash(any());
    }

    @org.junit.jupiter.api.Test
    void prepareRun_armingDeletion_deleteOwn_foreignDatabase_returns403() {
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(userWith(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN),
                        managedDatabaseRepository);
        var foreign = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        foreign.setCreatedBy("bob");
        when(managedDatabaseRepository.find("myapp", "mydb")).thenReturn(java.util.Optional.of(foreign));

        assertEquals(403, controller.prepareRun(runRequestArmingDeletion()).getStatus());
    }

    @org.junit.jupiter.api.Test
    void prepareRun_armingDeletion_deleteOwn_ownDatabase_passes() {
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(userWith(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN),
                        managedDatabaseRepository);
        var mine = new br.com.fzdevx.domain.model.ManagedDatabase("myapp", "mydb");
        mine.setCreatedBy("alice");
        when(managedDatabaseRepository.find("myapp", "mydb")).thenReturn(java.util.Optional.of(mine));
        when(requestStash.stash(any())).thenReturn("ticket-arm");

        assertEquals(200, controller.prepareRun(runRequestArmingDeletion()).getStatus());
    }

    @org.junit.jupiter.api.Test
    void prepareRun_armingDeletion_deleteOwn_databaseThisRequestCreates_passes() {
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(userWith(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE_OWN),
                        managedDatabaseRepository);
        when(requestStash.stash(any())).thenReturn("ticket-new");

        RunContainerRequest req = runRequestArmingDeletion();
        req.setDumpId("550e8400-e29b-41d4-a716-446655440000");
        req.setCreateDatabase(true);

        assertEquals(200, controller.prepareRun(req).getStatus());
    }

    @org.junit.jupiter.api.Test
    void prepareRun_armingDeletion_withoutADatabase_returns400() {
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(userWith(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE),
                        managedDatabaseRepository);
        RunContainerRequest req = runRequestArmingDeletion();
        req.setDatabaseName(null);

        assertEquals(400, controller.prepareRun(req).getStatus());
    }

    @Test
    void prepareRemove_invalidContainerId_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("INVALID!");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withoutDeleteDatabase_stashesAndReturnsTicket() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(false);

        when(requestStash.stashRemove(any())).thenReturn("ticket-123");

        Response res = controller.prepareRemove(req);

        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) res.getEntity();
        assertEquals("ticket-123", body.get("ticket"));
        verify(dumpStorageService, never()).validateOperationsPassword(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_validPassword_stashesAndReturnsTicket() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("testdb");
        req.setOperationsPassword("correct-pw");

        when(dumpStorageService.validateOperationsPassword("correct-pw")).thenReturn(true);
        when(requestStash.stashRemove(any())).thenReturn("ticket-456");

        Response res = controller.prepareRemove(req);

        assertEquals(200, res.getStatus());
        ArgumentCaptor<RemoveContainerRequest> captor = ArgumentCaptor.forClass(RemoveContainerRequest.class);
        verify(requestStash).stashRemove(captor.capture());
        assertNull(captor.getValue().getOperationsPassword(), "Password should be cleared before stashing");
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidPassword_returns403() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("testdb");
        req.setOperationsPassword("wrong-pw");

        when(dumpStorageService.validateOperationsPassword("wrong-pw")).thenReturn(false);

        Response res = controller.prepareRemove(req);

        assertEquals(403, res.getStatus());
        verify(requestStash, never()).stashRemove(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_nullRepository_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository(null);
        req.setDatabaseName("testdb");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
        verify(requestStash, never()).stashRemove(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidRepository_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("INVALID_REPO!");
        req.setDatabaseName("testdb");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withDeleteDatabase_nullDatabaseName_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName(null);

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidDatabaseName_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("123-invalid");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    // ---- guards that close privilege holes ----

    @org.junit.jupiter.api.Test
    void prepareRun_ignoresAClientSuppliedValidationFlag() {
        // the flag is a field on the deserialised request with a public setter,
        // so a caller can post it as true and skip the operations password
        br.com.fzdevx.application.dto.RunContainerRequest req =
                new br.com.fzdevx.application.dto.RunContainerRequest();
        req.setOperationsPasswordValidated(true);
        when(requestStash.stash(any())).thenReturn("ticket-1");

        controller.prepareRun(req);

        org.mockito.ArgumentCaptor<br.com.fzdevx.application.dto.RunContainerRequest> stashed =
                org.mockito.ArgumentCaptor.forClass(br.com.fzdevx.application.dto.RunContainerRequest.class);
        verify(requestStash).stash(stashed.capture());
        assertFalse(stashed.getValue().isOperationsPasswordValidated(),
                "only a validated password may set this");
    }

    @org.junit.jupiter.api.Test
    void prepareRun_grantsTheFlagOnlyAgainstAValidPassword() {
        br.com.fzdevx.application.dto.RunContainerRequest req =
                new br.com.fzdevx.application.dto.RunContainerRequest();
        req.setOperationsPassword("secret");
        when(dumpStorageService.validateOperationsPassword("secret")).thenReturn(true);
        when(requestStash.stash(any())).thenReturn("ticket-1");

        controller.prepareRun(req);

        org.mockito.ArgumentCaptor<br.com.fzdevx.application.dto.RunContainerRequest> stashed =
                org.mockito.ArgumentCaptor.forClass(br.com.fzdevx.application.dto.RunContainerRequest.class);
        verify(requestStash).stash(stashed.capture());
        assertTrue(stashed.getValue().isOperationsPasswordValidated());
        assertNull(stashed.getValue().getOperationsPassword(), "the password is not stashed");
    }

    @org.junit.jupiter.api.Test
    void prepareRemove_checksTheDatabaseIsVisibleNotJustTheContainer() {
        // the container being visible says nothing about the database named
        // alongside it in the same request
        var foreign = new br.com.fzdevx.domain.model.ManagedDatabase();
        foreign.setTenantId("other-tenant");
        when(managedDatabaseRepository.find("myapp", "mydb"))
                .thenReturn(java.util.Optional.of(foreign));
        var scoped = new br.com.fzdevx.infrastructure.config.CurrentUser();
        scoped.set("u1", "alice", java.util.Set.of(
                br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_RUN,
                br.com.fzdevx.domain.model.auth.Permission.DATABASE_DELETE),
                java.util.Set.of("my-tenant"));
        controller.deletionPolicy = br.com.fzdevx.infrastructure.config.TestDeletionPolicy
                .forUser(scoped, managedDatabaseRepository);
        controller.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(scoped, null);
        when(dumpStorageService.validateOperationsPassword(any())).thenReturn(true);

        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myapp");
        req.setDatabaseName("mydb");
        req.setOperationsPassword("secret");

        assertThrows(br.com.fzdevx.domain.exception.EntityNotFoundException.class,
                () -> controller.prepareRemove(req));
        verify(requestStash, never()).stashRemove(any());
    }
}
