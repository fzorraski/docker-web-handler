package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.DatabaseConflict;
import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerExpirationControllerTest {

    @Mock DockerClient dockerClient;
    @Mock ContainerExpirationService expirationService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock ContainerProtectionService protectionService;
    @Mock PasswordValidationService passwordValidationService;
    @Mock DatabaseService databaseService;

    @InjectMocks
    ContainerExpirationController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
    }

    private DockerContainer req(String id) {
        DockerContainer dc = new DockerContainer();
        dc.setContainerId(id);
        return dc;
    }

    // ---- extendExpiration ----

    @Test
    void extendExpiration_invalidId_returnsFalse() {
        assertFalse(controller.extendExpiration(req("BAD!"), 10));
    }

    @Test
    void extendExpiration_minutesTooLow_returnsFalse() {
        assertFalse(controller.extendExpiration(req("abc123def4"), 0));
    }

    @Test
    void extendExpiration_minutesTooHigh_returnsFalse() {
        assertFalse(controller.extendExpiration(req("abc123def4"), 1441));
    }

    @Test
    void extendExpiration_valid_delegates() {
        when(expirationService.extendExpiration("abc123def4", 10)).thenReturn(true);
        assertTrue(controller.extendExpiration(req("abc123def4"), 10));
    }

    @Test
    void extendExpiration_serviceFails_returnsFalse() {
        when(expirationService.extendExpiration("abc123def4", 10)).thenReturn(false);
        assertFalse(controller.extendExpiration(req("abc123def4"), 10));
    }

    @Test
    void extendExpiration_protected_returnsFalseAndDoesNotExtend() {
        when(protectionService.isProtectedContainer("abc123def4")).thenReturn(true);
        assertFalse(controller.extendExpiration(req("abc123def4"), 10));
        verify(expirationService, never()).extendExpiration(anyString(), anyInt());
    }

    // ---- cancelDatabaseDeletion ----

    @Test
    void cancelDatabaseDeletion_invalidId_returnsFalse() {
        assertFalse(controller.cancelDatabaseDeletion(req("BAD!")));
    }

    @Test
    void cancelDatabaseDeletion_valid_delegates() {
        when(expirationService.disableDatabaseDeletion("abc123def4")).thenReturn(true);
        assertTrue(controller.cancelDatabaseDeletion(req("abc123def4")));
    }

    // ---- cancelExpiration ----

    @Test
    void cancelExpiration_invalidId_returnsFalse() {
        assertFalse(controller.cancelExpiration(req("BAD!")));
    }

    @Test
    void cancelExpiration_valid_cancelsAndReturnsTrue() {
        assertTrue(controller.cancelExpiration(req("abc123def4")));
        verify(expirationService).cancel("abc123def4");
    }

    // ---- getDatabaseConflicts ----

    @Test
    void getDatabaseConflicts_invalidDbName_returnsEmpty() {
        var result = controller.getDatabaseConflicts("123bad");
        assertTrue(result.getInUseByContainers().isEmpty());
        assertNull(result.getScheduledForDeletionBy());
    }

    @Test
    void getDatabaseConflicts_noExpirations_returnsEmpty() {
        when(expirationService.findByDatabaseName("mydb")).thenReturn(Collections.emptyList());
        var result = controller.getDatabaseConflicts("mydb");
        assertTrue(result.getInUseByContainers().isEmpty());
    }

    @Test
    void getDatabaseConflicts_noExpirations_returnsProtectedFlag() {
        when(expirationService.findByDatabaseName("mydb")).thenReturn(Collections.emptyList());
        ManagedDatabase md = new ManagedDatabase("repo", "mydb");
        md.setProtectedFlag(true);
        when(managedDatabaseRepository.findAll()).thenReturn(List.of(md));

        var result = controller.getDatabaseConflicts("mydb");
        assertTrue(result.isProtectedFlag());
    }

    @Test
    void getDatabaseConflicts_noExpirations_notProtected_returnsFalse() {
        when(expirationService.findByDatabaseName("mydb")).thenReturn(Collections.emptyList());
        when(managedDatabaseRepository.findAll()).thenReturn(List.of());

        var result = controller.getDatabaseConflicts("mydb");
        assertFalse(result.isProtectedFlag());
    }

    @Test
    void getDatabaseConflicts_withExpirations_returnsProtectedFlag() {
        ContainerExpiration exp = new ContainerExpiration("abc123", "full123",
                Instant.now().plusSeconds(3600), "repo", "mydb", false);
        when(expirationService.findByDatabaseName("mydb")).thenReturn(List.of(exp));

        ManagedDatabase md = new ManagedDatabase("repo", "mydb");
        md.setProtectedFlag(true);
        when(managedDatabaseRepository.find("repo", "mydb")).thenReturn(Optional.of(md));

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(Collections.emptyList());

        var result = controller.getDatabaseConflicts("mydb");
        assertTrue(result.isProtectedFlag());
        assertEquals(1, result.getInUseByContainers().size());
    }

    @Test
    void getDatabaseConflicts_withExpirations_notProtected() {
        ContainerExpiration exp = new ContainerExpiration("abc123", "full123",
                Instant.now().plusSeconds(3600), "repo", "mydb", false);
        when(expirationService.findByDatabaseName("mydb")).thenReturn(List.of(exp));
        when(managedDatabaseRepository.find("repo", "mydb")).thenReturn(Optional.empty());

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(Collections.emptyList());

        var result = controller.getDatabaseConflicts("mydb");
        assertFalse(result.isProtectedFlag());
    }

    @Test
    void getDatabaseConflicts_withDeletionScheduled_returnsScheduledBy() {
        ContainerExpiration exp = new ContainerExpiration("abc123", "full123",
                Instant.now().plusSeconds(3600), "repo", "mydb", true);
        when(expirationService.findByDatabaseName("mydb")).thenReturn(List.of(exp));
        when(managedDatabaseRepository.find("repo", "mydb")).thenReturn(Optional.empty());

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(Collections.emptyList());

        var result = controller.getDatabaseConflicts("mydb");
        assertNotNull(result.getScheduledForDeletionBy());
        assertNotNull(result.getExpiresAt());
    }

    // ---- updateExpiration ----

    private ContainerExpirationController.UpdateExpirationRequest updateReq(
            String id, String expiresAt, boolean deleteDb, String password) {
        var req = new ContainerExpirationController.UpdateExpirationRequest();
        req.containerId = id;
        req.expiresAt = expiresAt;
        req.deleteDatabaseOnExpiration = deleteDb;
        req.operationsPassword = password;
        return req;
    }

    @Test
    void updateExpiration_nullRequest_returns400() {
        Response resp = controller.updateExpiration(null);
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_invalidId_returns400() {
        Response resp = controller.updateExpiration(updateReq("BAD!", null, false, null));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_invalidDateFormat_returns400() {
        Response resp = controller.updateExpiration(updateReq("abc123def4", "not-a-date", false, null));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_pastDate_returns400() {
        String past = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Response resp = controller.updateExpiration(updateReq("abc123def4", past, false, null));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_removeExpiration_success() {
        when(expirationService.updateExpiration(eq("abc123def4"), isNull(), eq(false))).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", null, false, null));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void updateExpiration_setNewTime_success() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(expirationService.updateExpiration(eq("abc123def4"), any(Instant.class), eq(false))).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, false, null));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void updateExpiration_protectedContainer_returns400() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(protectionService.isProtectedContainer("abc123def4")).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, false, null));
        assertEquals(400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getEntity();
        assertTrue(body.get("error").contains("protected"));
        verify(expirationService, never()).updateExpiration(anyString(), any(), anyBoolean());
    }

    @Test
    void updateExpiration_protectedContainer_clearingStillAllowed() {
        // Clearing (null expiration) is allowed even when protected.
        when(protectionService.isProtectedContainer("abc123def4")).thenReturn(true);
        when(expirationService.updateExpiration(eq("abc123def4"), isNull(), eq(false))).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", null, false, null));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void updateExpiration_notFound_returns404() {
        when(expirationService.updateExpiration(eq("abc123def4"), isNull(), eq(false))).thenReturn(false);
        Response resp = controller.updateExpiration(updateReq("abc123def4", null, false, null));
        assertEquals(404, resp.getStatus());
    }

    @Test
    void updateExpiration_enableDbDeletion_noExpiration_returns400() {
        Response resp = controller.updateExpiration(updateReq("abc123def4", null, true, "pass"));
        assertEquals(400, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getEntity();
        assertTrue(body.get("error").contains("without expiration"));
    }

    @Test
    void updateExpiration_enableDbDeletion_featureDisabled_returns400() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(false);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, true, "pass"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_enableDbDeletion_noDatabase_returns400() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(true);
        when(expirationService.getDatabaseName("abc123def4")).thenReturn(null);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, true, "pass"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void updateExpiration_enableDbDeletion_invalidPassword_returns403() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(true);
        when(expirationService.getDatabaseName("abc123def4")).thenReturn("testdb");
        when(passwordValidationService.isOperationsPasswordRequired()).thenReturn(true);
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, true, "wrong"));
        assertEquals(403, resp.getStatus());
    }

    @Test
    void updateExpiration_enableDbDeletion_validPassword_success() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(true);
        when(expirationService.getDatabaseName("abc123def4")).thenReturn("testdb");
        when(passwordValidationService.isOperationsPasswordRequired()).thenReturn(true);
        when(passwordValidationService.validateOperationsPassword("correct")).thenReturn(true);
        when(expirationService.updateExpiration(eq("abc123def4"), any(Instant.class), eq(true))).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, true, "correct"));
        assertEquals(200, resp.getStatus());
    }

    @Test
    void updateExpiration_enableDbDeletion_passwordNotRequired_success() {
        String future = LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(true);
        when(expirationService.getDatabaseName("abc123def4")).thenReturn("testdb");
        when(passwordValidationService.isOperationsPasswordRequired()).thenReturn(false);
        when(expirationService.updateExpiration(eq("abc123def4"), any(Instant.class), eq(true))).thenReturn(true);
        Response resp = controller.updateExpiration(updateReq("abc123def4", future, true, null));
        assertEquals(200, resp.getStatus());
    }
}
