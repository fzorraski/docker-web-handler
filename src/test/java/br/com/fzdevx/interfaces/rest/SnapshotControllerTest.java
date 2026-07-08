package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.application.usecase.CreateSnapshotUseCase;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotControllerTest {

    private static final String VALID_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_PASSWORD = "secret";

    @Mock SnapshotStorageService snapshotStorageService;
    @Mock DumpStorageService dumpStorageService;
    @Mock DatabaseService databaseService;
    @Mock CreateSnapshotUseCase createSnapshotUseCase;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;

    @InjectMocks
    SnapshotController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
    }

    // ---- listSnapshots ----

    @Test
    void listSnapshots_enabled_returnsList() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseSnapshot s = new DatabaseSnapshot();
        when(snapshotStorageService.findAll()).thenReturn(List.of(s));

        assertEquals(1, controller.listSnapshots().size());
    }

    @Test
    void listSnapshots_disabled_returnsEmpty() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertTrue(controller.listSnapshots().isEmpty());
    }

    @Test
    void listSnapshots_filtersTemporarySnapshots() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseSnapshot permanent = new DatabaseSnapshot("pg", "db1", DatabaseSnapshot.Format.CUSTOM, "keep");
        DatabaseSnapshot temporary = new DatabaseSnapshot("pg", "db2", DatabaseSnapshot.Format.CUSTOM, "temp");
        temporary.setTemporary(true);
        when(snapshotStorageService.findAll()).thenReturn(List.of(permanent, temporary));

        List<DatabaseSnapshot> result = controller.listSnapshots();
        assertEquals(1, result.size());
        assertEquals("keep", result.getFirst().getLabel());
    }

    // ---- downloadSnapshot ----

    @Test
    void downloadSnapshot_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.downloadSnapshot(VALID_UUID).getStatus());
    }

    @Test
    void downloadSnapshot_invalidUuid_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        assertEquals(400, controller.downloadSnapshot("bad").getStatus());
    }

    @Test
    void downloadSnapshot_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertEquals(404, controller.downloadSnapshot(VALID_UUID).getStatus());
    }

    @Test
    void downloadSnapshot_fileMissing_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseSnapshot s = new DatabaseSnapshot("pg", "mydb", DatabaseSnapshot.Format.CUSTOM, "label");
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.of(s));
        File file = mock(File.class);
        when(file.exists()).thenReturn(false);
        when(snapshotStorageService.getStoredFile(s)).thenReturn(file);

        assertEquals(404, controller.downloadSnapshot(VALID_UUID).getStatus());
    }

    @Test
    void downloadSnapshot_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseSnapshot s = new DatabaseSnapshot("pg", "mydb", DatabaseSnapshot.Format.CUSTOM, "label");
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.of(s));
        File file = mock(File.class);
        when(file.exists()).thenReturn(true);
        when(file.length()).thenReturn(2048L);
        when(snapshotStorageService.getStoredFile(s)).thenReturn(file);

        Response response = controller.downloadSnapshot(VALID_UUID);
        assertEquals(200, response.getStatus());
        verify(snapshotStorageService).markUsed(VALID_UUID);
    }

    // ---- deleteSnapshot ----

    @Test
    void deleteSnapshot_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.deleteSnapshot(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    @Test
    void deleteSnapshot_invalidUuid_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        assertEquals(400, controller.deleteSnapshot("bad", VALID_PASSWORD).getStatus());
    }

    @Test
    void deleteSnapshot_invalidPassword_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.deleteSnapshot(VALID_UUID, "wrong").getStatus());
    }

    @Test
    void deleteSnapshot_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.empty());
        assertEquals(404, controller.deleteSnapshot(VALID_UUID, VALID_PASSWORD).getStatus());
    }

    @Test
    void deleteSnapshot_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseSnapshot()));

        assertEquals(200, controller.deleteSnapshot(VALID_UUID, VALID_PASSWORD).getStatus());
        verify(snapshotStorageService).deleteSnapshot(VALID_UUID);
    }

    // ---- deleteBulk ----

    @Test
    void deleteBulk_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.deleteBulk(VALID_PASSWORD, List.of(VALID_UUID)).getStatus());
    }

    @Test
    void deleteBulk_nullIds_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        assertEquals(400, controller.deleteBulk(VALID_PASSWORD, null).getStatus());
    }

    @Test
    void deleteBulk_emptyIds_returnsBadRequest() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        assertEquals(400, controller.deleteBulk(VALID_PASSWORD, List.of()).getStatus());
    }

    @Test
    void deleteBulk_invalidPassword_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.deleteBulk("wrong", List.of(VALID_UUID)).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteBulk_valid_deletesAndReturnsCount() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.findById(VALID_UUID)).thenReturn(Optional.of(new DatabaseSnapshot()));

        Response response = controller.deleteBulk(VALID_PASSWORD, List.of(VALID_UUID));
        assertEquals(200, response.getStatus());
        assertEquals(1, ((Map<String, Object>) response.getEntity()).get("deleted"));
    }

    // ---- updateMetadata ----

    @Test
    void updateMetadata_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.updateMetadata(VALID_UUID, VALID_PASSWORD, Map.of()).getStatus());
    }

    @Test
    void updateMetadata_notFound_returns404() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.updateMetadata(eq(VALID_UUID), any(), any())).thenReturn(false);

        assertEquals(404, controller.updateMetadata(VALID_UUID, VALID_PASSWORD, Map.of()).getStatus());
    }

    @Test
    void updateMetadata_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.updateMetadata(eq(VALID_UUID), any(), any())).thenReturn(true);

        assertEquals(200, controller.updateMetadata(VALID_UUID, VALID_PASSWORD,
                Map.of("label", "test")).getStatus());
    }

    // ---- updateExpiration ----

    @Test
    void updateExpiration_valid_returns200() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword(VALID_PASSWORD)).thenReturn(true);
        when(snapshotStorageService.updateExpiration(eq(VALID_UUID), any())).thenReturn(true);

        assertEquals(200, controller.updateExpiration(VALID_UUID, VALID_PASSWORD,
                Map.of("expiresAt", "2025-12-31T23:59:00")).getStatus());
    }

    // ---- getStorageInfo ----

    @Test
    void getStorageInfo_disabled_returnsZeros() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        Map<String, Object> result = controller.getStorageInfo();
        assertEquals(0, result.get("totalBytes"));
    }

    @Test
    void getStorageInfo_enabled_returnsInfo() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(snapshotStorageService.findAll()).thenReturn(List.of(new DatabaseSnapshot()));
        when(snapshotStorageService.getTotalStorageBytes()).thenReturn(3000L);
        when(snapshotStorageService.getMaxSizeMb()).thenReturn(200);

        Map<String, Object> result = controller.getStorageInfo();
        assertEquals(3000L, result.get("totalBytes"));
        assertEquals(1L, result.get("fileCount"));
    }

    @Test
    void getStorageInfo_excludesTemporaryFromCount() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        DatabaseSnapshot permanent = new DatabaseSnapshot();
        DatabaseSnapshot temporary = new DatabaseSnapshot();
        temporary.setTemporary(true);
        when(snapshotStorageService.findAll()).thenReturn(List.of(permanent, temporary));
        when(snapshotStorageService.getTotalStorageBytes()).thenReturn(5000L);
        when(snapshotStorageService.getMaxSizeMb()).thenReturn(200);

        Map<String, Object> result = controller.getStorageInfo();
        assertEquals(1L, result.get("fileCount"));
    }

    // ---- getSnapshotRepositories ----

    @Test
    void getSnapshotRepositories_disabled_returnsEmpty() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertTrue(controller.getSnapshotRepositories().isEmpty());
    }

    @Test
    void getSnapshotRepositories_filtersWithDbConfig() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("pg", "redis"));
        when(databaseService.hasDatabaseConfig("pg")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("redis")).thenReturn(false);

        assertEquals(List.of("pg"), controller.getSnapshotRepositories());
    }

    // ---- getActiveSnapshots ----

    @Test
    void getActiveSnapshots_delegatesToUseCase() {
        when(createSnapshotUseCase.getActiveSnapshots()).thenReturn(List.of(
                new CreateSnapshotUseCase.ActiveSnapshotInfo("pg", "mydb")));

        var result = controller.getActiveSnapshots();
        assertEquals(1, result.size());
        assertEquals("pg", result.getFirst().get("repository"));
    }

    // ---- cancelSnapshot ----

    @Test
    void cancelSnapshot_missingParams_returnsBadRequest() {
        assertEquals(400, controller.cancelSnapshot(Map.of()).getStatus());
    }

    @Test
    void cancelSnapshot_notFound_returns404() {
        when(createSnapshotUseCase.cancel("pg", "mydb")).thenReturn(false);
        assertEquals(404, controller.cancelSnapshot(
                Map.of("repository", "pg", "sourceDatabaseName", "mydb")).getStatus());
    }

    @Test
    void cancelSnapshot_valid_returns200() {
        when(createSnapshotUseCase.cancel("pg", "mydb")).thenReturn(true);
        assertEquals(200, controller.cancelSnapshot(
                Map.of("repository", "pg", "sourceDatabaseName", "mydb")).getStatus());
    }

    // ---- cleanupIdleSnapshots ----

    @Test
    void cleanupIdleSnapshots_disabled_returnsForbidden() {
        when(dumpStorageService.isEnabled()).thenReturn(false);
        assertEquals(403, controller.cleanupIdleSnapshots(Map.of("password", "x", "minDays", 1)).getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupIdleSnapshots_deletesOldSnapshots() {
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(dumpStorageService.validateOperationsPassword("secret")).thenReturn(true);
        DatabaseSnapshot old = new DatabaseSnapshot();
        old.setId("old-id");
        old.setCreatedAt(Instant.now().minusSeconds(86400 * 60));
        when(snapshotStorageService.findAll()).thenReturn(List.of(old));

        Response response = controller.cleanupIdleSnapshots(Map.of("password", "secret", "minDays", 30));
        assertEquals(200, response.getStatus());
        assertEquals(1, ((Map<String, Object>) response.getEntity()).get("deleted"));
    }
}
