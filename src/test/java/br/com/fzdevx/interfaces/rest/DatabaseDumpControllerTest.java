package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.infrastructure.docker.PostRestoreScriptService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatabaseDumpControllerTest {

    private static final String VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_PASSWORD = "secret";

    @Mock DumpStorageService dumpStorageService;
    @Mock DatabaseService databaseService;
    @Mock RestoreDumpUseCase restoreDumpUseCase;
    @Mock PostRestoreScriptService postRestoreScriptService;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;
    @Mock ResourceCounterService resourceCounterService;
    @Mock br.com.fzdevx.application.port.AuditLogger auditLogger;

    @InjectMocks
    DatabaseDumpController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        controller.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
        controller.tenantEntitlements = br.com.fzdevx.infrastructure.config.TestTenantEntitlements.passthrough();
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_delegatesToService() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        assertTrue(controller.isEnabled());
    }

    @Test
    void isEnabled_returnsFalseWhenDisabled() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertFalse(controller.isEnabled());
    }

    // ---- listDumps ----

    @Test
    void updateSharing_shareRecipient_cannotReshare() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setId(VALID_UUID);
        dump.setTenantId("owner-tenant");
        dump.setSharedWithTenants(java.util.List.of("recipient-tenant"));
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(dump));

        var recipient = new br.com.fzdevx.infrastructure.config.CurrentUser();
        recipient.set("u1", "bob",
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.DATABASE_VIEW,
                        br.com.fzdevx.domain.model.auth.Permission.DATABASE_OPERATE),
                java.util.Set.of("recipient-tenant"));
        controller.currentUser = recipient;
        controller.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(recipient, null);

        // visible via the share, but only the owning tenant may change sharing
        Response response = controller.updateSharing(VALID_UUID,
                Map.of("sharedWithTenants", java.util.List.of("third-tenant")));

        assertEquals(403, response.getStatus());
        verify(dumpStorageService, never()).updateSharing(any(), any(), any(), anyBoolean());
    }

    @Test
    void updateSharing_ownerTenantMember_allowed() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setId(VALID_UUID);
        dump.setTenantId("owner-tenant");
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(dump));
        when(dumpStorageService.updateSharing(eq(VALID_UUID), any(), any(), anyBoolean())).thenReturn(true);

        var owner = new br.com.fzdevx.infrastructure.config.CurrentUser();
        owner.set("u1", "alice",
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.DATABASE_VIEW,
                        br.com.fzdevx.domain.model.auth.Permission.DATABASE_OPERATE),
                java.util.Set.of("owner-tenant"));
        controller.currentUser = owner;
        controller.tenantVisibility =
                br.com.fzdevx.infrastructure.config.TestTenantVisibility.forUser(owner, null);

        Response response = controller.updateSharing(VALID_UUID, Map.of("sharedWithTenants", java.util.List.of()));

        assertEquals(200, response.getStatus());
    }

    @Test
    void listDumps_enabled_returnsList() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setId(VALID_UUID);
        when(dumpStorageService.findAll()).thenReturn(List.of(dump));

        List<DatabaseDump> result = controller.listDumps();

        assertEquals(1, result.size());
    }

    @Test
    void listDumps_disabled_returnsEmpty() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        assertTrue(controller.listDumps().isEmpty());
        verify(dumpStorageService, never()).findAll();
    }

    // ---- downloadDump ----

    @Test
    void downloadDump_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Response response = controller.downloadDump(VALID_UUID);

        assertEquals(403, response.getStatus());
    }

    @Test
    void downloadDump_invalidUuid_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);

        Response response = controller.downloadDump("not-a-uuid");

        assertEquals(400, response.getStatus());
    }

    @Test
    void downloadDump_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.empty());

        Response response = controller.downloadDump(VALID_UUID);

        assertEquals(404, response.getStatus());
    }

    @Test
    void downloadDump_fileDoesNotExist_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setOriginalFilename("backup.sql");
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(dump));
        File missingFile = mock(File.class);
        when(missingFile.exists()).thenReturn(false);
        when(dumpStorageService.getStoredFile(dump)).thenReturn(missingFile);

        Response response = controller.downloadDump(VALID_UUID);

        assertEquals(404, response.getStatus());
    }

    @Test
    void downloadDump_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setOriginalFilename("backup.sql");
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(dump));
        File file = mock(File.class);
        when(file.exists()).thenReturn(true);
        when(file.length()).thenReturn(1024L);
        when(dumpStorageService.getStoredFile(dump)).thenReturn(file);

        Response response = controller.downloadDump(VALID_UUID);

        assertEquals(200, response.getStatus());
        verify(dumpStorageService).markUsed(VALID_UUID);
    }

    // ---- deleteDump ----

    @Test
    void deleteDump_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Response response = controller.deleteDump(VALID_UUID, VALID_PASSWORD);

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteDump_invalidUuid_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);

        Response response = controller.deleteDump("bad", VALID_PASSWORD);

        assertEquals(400, response.getStatus());
    }

    @Test
    void deleteDump_invalidPassword_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("wrong")).thenReturn(false);

        Response response = controller.deleteDump(VALID_UUID, "wrong");

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteDump_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.empty());

        Response response = controller.deleteDump(VALID_UUID, VALID_PASSWORD);

        assertEquals(404, response.getStatus());
    }

    @Test
    void deleteDump_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseDump()));

        Response response = controller.deleteDump(VALID_UUID, VALID_PASSWORD);

        assertEquals(200, response.getStatus());
        verify(dumpStorageService).deleteDump(VALID_UUID);
    }

    // ---- deleteBulk ----

    @Test
    void deleteBulk_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Response response = controller.deleteBulk(VALID_PASSWORD, List.of(VALID_UUID));

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteBulk_emptyIds_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);

        Response response = controller.deleteBulk(VALID_PASSWORD, List.of());

        assertEquals(400, response.getStatus());
    }

    @Test
    void deleteBulk_nullIds_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);

        Response response = controller.deleteBulk(VALID_PASSWORD, null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void deleteBulk_invalidPassword_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("wrong")).thenReturn(false);

        Response response = controller.deleteBulk("wrong", List.of(VALID_UUID));

        assertEquals(403, response.getStatus());
    }

    @Test
    void deleteBulk_valid_deletesAndReturnsCount() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        DatabaseDump dump = new DatabaseDump();
        dump.setId(VALID_UUID);
        when(dumpStorageService.findAll()).thenReturn(List.of(dump));

        Response response = controller.deleteBulk(VALID_PASSWORD, List.of(VALID_UUID));

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(1, entity.get("deleted"));
    }

    @Test
    void deleteBulk_skipsInvalidUuids() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);

        Response response = controller.deleteBulk(VALID_PASSWORD, List.of("invalid-id"));

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(0, entity.get("deleted"));
    }

    // ---- updateMetadata ----

    @Test
    void updateMetadata_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Response response = controller.updateMetadata(VALID_UUID, VALID_PASSWORD, Map.of());

        assertEquals(403, response.getStatus());
    }

    @Test
    void updateMetadata_invalidUuid_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);

        Response response = controller.updateMetadata("bad", VALID_PASSWORD, Map.of());

        assertEquals(400, response.getStatus());
    }

    @Test
    void updateMetadata_invalidDbName_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseDump()));

        Response response = controller.updateMetadata(VALID_UUID, VALID_PASSWORD,
                Map.of("databaseName", "123bad"));

        assertEquals(400, response.getStatus());
    }

    @Test
    void updateMetadata_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.updateMetadata(eq(VALID_UUID), any(), any(), any())).thenReturn(false);

        Response response = controller.updateMetadata(VALID_UUID, VALID_PASSWORD, Map.of());

        assertEquals(404, response.getStatus());
    }

    @Test
    void updateMetadata_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseDump()));
        when(dumpStorageService.updateMetadata(eq(VALID_UUID), any(), any(), any())).thenReturn(true);

        Response response = controller.updateMetadata(VALID_UUID, VALID_PASSWORD,
                Map.of("version", "1.0", "databaseName", "mydb"));

        assertEquals(200, response.getStatus());
    }

    // ---- updateExpiration ----

    @Test
    void updateExpiration_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(dumpStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseDump()));
        when(dumpStorageService.updateExpiration(eq(VALID_UUID), any())).thenReturn(true);

        Response response = controller.updateExpiration(VALID_UUID, VALID_PASSWORD,
                Map.of("expiresAt", "2025-12-31T23:59:00"));

        assertEquals(200, response.getStatus());
    }

    // ---- getStorageInfo ----

    @Test
    void getStorageInfo_disabled_returnsZeros() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Map<String, Object> result = controller.getStorageInfo();

        assertEquals(0, result.get("totalBytes"));
        assertEquals(0, result.get("fileCount"));
    }

    @Test
    void getStorageInfo_enabled_returnsInfo() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.findAll()).thenReturn(List.of(new DatabaseDump(), new DatabaseDump()));
        when(dumpStorageService.getTotalStorageBytes()).thenReturn(5000L);
        when(dumpStorageService.getMaxSizeMb()).thenReturn(100);

        Map<String, Object> result = controller.getStorageInfo();

        assertEquals(5000L, result.get("totalBytes"));
        assertEquals(2, result.get("fileCount"));
    }

    // ---- getDumpRepositories ----

    @Test
    void getDumpRepositories_disabled_returnsEmpty() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        assertTrue(controller.getDumpRepositories().isEmpty());
    }

    @Test
    void getDumpRepositories_filtersWithDbConfig() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("postgres", "redis"));
        when(databaseService.hasDatabaseConfig("postgres")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("redis")).thenReturn(false);

        List<String> result = controller.getDumpRepositories();

        assertEquals(1, result.size());
        assertEquals("postgres", result.getFirst());
    }

    // ---- getActiveRestores ----

    @Test
    void getActiveRestores_delegatesToUseCase() {
        when(restoreDumpUseCase.getActiveRestores()).thenReturn(List.of(
                new RestoreDumpUseCase.ActiveRestoreInfo("pg", "mydb", "backup.sql")
        ));

        var result = controller.getActiveRestores();

        assertEquals(1, result.size());
        assertEquals("pg", result.getFirst().get("repository"));
    }

    // ---- cancelRestore ----

    @Test
    void cancelRestore_missingParams_returnsBadRequest() {
        Response response = controller.cancelRestore(Map.of());
        assertEquals(400, response.getStatus());
    }

    @Test
    void cancelRestore_blankParams_returnsBadRequest() {
        Response response = controller.cancelRestore(Map.of("repository", "", "targetDatabase", ""));
        assertEquals(400, response.getStatus());
    }

    @Test
    void cancelRestore_noActiveRestore_returns404() {
        when(restoreDumpUseCase.cancel("pg", "mydb")).thenReturn(false);

        Response response = controller.cancelRestore(Map.of("repository", "pg", "targetDatabase", "mydb"));

        assertEquals(404, response.getStatus());
    }

    @Test
    void cancelRestore_valid_returns200() {
        when(restoreDumpUseCase.cancel("pg", "mydb")).thenReturn(true);

        Response response = controller.cancelRestore(Map.of("repository", "pg", "targetDatabase", "mydb"));

        assertEquals(200, response.getStatus());
    }

    // ---- cleanupIdleDumps ----

    @Test
    void cleanupIdleDumps_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);

        Response response = controller.cleanupIdleDumps(Map.of("password", "secret", "minDays", 30));

        assertEquals(403, response.getStatus());
    }

    @Test
    void cleanupIdleDumps_invalidPassword_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("wrong")).thenReturn(false);

        Response response = controller.cleanupIdleDumps(Map.of("password", "wrong", "minDays", 30));

        assertEquals(403, response.getStatus());
    }

    @Test
    void cleanupIdleDumps_deletesOldDumps() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("secret")).thenReturn(true);
        DatabaseDump oldDump = new DatabaseDump();
        oldDump.setId("old-id");
        oldDump.setUploadedAt(Instant.now().minusSeconds(86400 * 60));
        when(dumpStorageService.findAll()).thenReturn(List.of(oldDump));

        Response response = controller.cleanupIdleDumps(Map.of("password", "secret", "minDays", 30));

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertEquals(1, entity.get("deleted"));
    }
}
