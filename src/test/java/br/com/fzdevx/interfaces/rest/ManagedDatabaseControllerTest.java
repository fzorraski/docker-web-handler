package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.application.usecase.CleanupIdleDatabasesUseCase;
import br.com.fzdevx.application.usecase.ListManagedDatabasesUseCase;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagedDatabaseControllerTest {

    private static final String REPO = "myapp";
    private static final String DB_NAME = "testdb";
    private static final String PASSWORD = "secret";

    @Mock ListManagedDatabasesUseCase listManagedDatabasesUseCase;
    @Mock CleanupIdleDatabasesUseCase cleanupIdleDatabasesUseCase;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock DatabaseService databaseService;
    @Mock PasswordValidationService passwordValidationService;
    @Mock ContainerExpirationService expirationService;
    @Mock ResourceCounterService resourceCounterService;
    @Mock br.com.fzdevx.application.port.AuditLogger auditLogger;

    @InjectMocks
    ManagedDatabaseController controller;

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        controller.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
        controller.tenantEntitlements = br.com.fzdevx.infrastructure.config.TestTenantEntitlements.passthrough();
    }

    @BeforeEach
    void setUp() {
        setField("managedEnabled", true);
        when(passwordValidationService.validateOperationsPassword(PASSWORD)).thenReturn(true);
        when(databaseService.hasDatabaseConfig(REPO)).thenReturn(true);
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_returnsTrue_whenEnabled() {
        assertTrue(controller.isEnabled());
    }

    @Test
    void isEnabled_returnsFalse_whenDisabled() {
        setField("managedEnabled", false);
        assertFalse(controller.isEnabled());
    }

    // ---- getRepositories ----

    @Test
    void getRepositories_disabled_returnsEmpty() {
        setField("managedEnabled", false);
        assertTrue(controller.getRepositories().isEmpty());
    }

    @Test
    void getRepositories_enabled_delegatesToUseCase() {
        when(listManagedDatabasesUseCase.getRepositories()).thenReturn(List.of("repo1", "repo2"));
        assertEquals(2, controller.getRepositories().size());
    }

    // ---- listDatabases ----

    @Test
    void listDatabases_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.listDatabases(REPO).getStatus());
    }

    @Test
    void listDatabases_invalidRepo_returns400() {
        assertEquals(400, controller.listDatabases("").getStatus());
    }

    @Test
    void listDatabases_noConfig_returns400() {
        when(databaseService.hasDatabaseConfig("unknown")).thenReturn(false);
        assertEquals(400, controller.listDatabases("unknown").getStatus());
    }

    @Test
    void listDatabases_success_returns200() {
        ManagedDatabaseInfo db = makeDb("mydb");
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(db));

        Response response = controller.listDatabases(REPO);
        assertEquals(200, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDatabases_stripsCreatorWithoutAuditView() {
        ManagedDatabaseInfo db = new ManagedDatabaseInfo("mydb", REPO, 1024L, 0, null, null,
                null, false, Instant.now(), null, 0, null, false, null, null, "alice", null);
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(db));
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "bob", java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.DATABASE_VIEW));
        controller.currentUser = rbacUser;

        List<ManagedDatabaseInfo> body =
                (List<ManagedDatabaseInfo>) controller.listDatabases(REPO).getEntity();

        assertEquals(1, body.size());
        assertNull(body.getFirst().createdBy(), "creator must be stripped without AUDIT_VIEW");
        assertEquals("alice", db.createdBy(), "the cached instance must not be mutated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDatabases_keepsCreatorWithAuditView() {
        ManagedDatabaseInfo db = new ManagedDatabaseInfo("mydb", REPO, 1024L, 0, null, null,
                null, false, Instant.now(), null, 0, null, false, null, null, "alice", null);
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(db));

        List<ManagedDatabaseInfo> body =
                (List<ManagedDatabaseInfo>) controller.listDatabases(REPO).getEntity();

        assertEquals("alice", body.getFirst().createdBy());
    }

    @Test
    void listDatabases_connectionError_returns503() {
        when(listManagedDatabasesUseCase.listDatabases(REPO))
                .thenThrow(new RuntimeException("Failed to connect to PostgreSQL"));

        Response response = controller.listDatabases(REPO);
        assertEquals(503, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) body.get("connectionError"));
    }

    @Test
    void listDatabases_genericError_returns503() {
        when(listManagedDatabasesUseCase.listDatabases(REPO))
                .thenThrow(new RuntimeException("Some other error"));

        Response response = controller.listDatabases(REPO);
        assertEquals(503, response.getStatus());
    }

    // ---- deleteDatabase ----

    @Test
    void deleteDatabase_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.deleteDatabase(REPO, DB_NAME, PASSWORD, false).getStatus());
    }

    @Test
    void deleteDatabase_invalidRepo_returns400() {
        assertEquals(400, controller.deleteDatabase("", DB_NAME, PASSWORD, false).getStatus());
    }

    @Test
    void deleteDatabase_invalidName_returns400() {
        assertEquals(400, controller.deleteDatabase(REPO, "", PASSWORD, false).getStatus());
    }

    @Test
    void deleteDatabase_wrongPassword_returns403() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.deleteDatabase(REPO, DB_NAME, "wrong", false).getStatus());
    }

    @Test
    void deleteDatabase_protected_returns409() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        md.setProtectedFlag(true);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));

        assertEquals(409, controller.deleteDatabase(REPO, DB_NAME, PASSWORD, false).getStatus());
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    @Test
    void deleteDatabase_success_returns200() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.empty());
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of());

        Response response = controller.deleteDatabase(REPO, DB_NAME, PASSWORD, false);
        assertEquals(200, response.getStatus());
        verify(databaseService).dropDatabase(REPO, DB_NAME);
        verify(managedDatabaseRepository).delete(REPO, DB_NAME);
        verify(listManagedDatabasesUseCase).invalidateCache(REPO);
    }

    @Test
    void deleteDatabase_inUseByContainer_returns409() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.empty());
        ContainerExpiration exp = new ContainerExpiration("c1", "full1",
                java.time.Instant.now(), REPO, DB_NAME, false);
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of(exp));

        Response response = controller.deleteDatabase(REPO, DB_NAME, PASSWORD, false);
        assertEquals(409, response.getStatus());
        verify(databaseService, never()).dropDatabase(any(), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("DATABASE_IN_USE", body.get("errorCode"));
    }

    @Test
    void deleteDatabase_activeConnections_requiresForce() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.empty());
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of());
        when(databaseService.getActiveConnectionCount(REPO, DB_NAME)).thenReturn(5);

        Response response = controller.deleteDatabase(REPO, DB_NAME, PASSWORD, false);
        assertEquals(409, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) body.get("requiresForce"));
        assertEquals(5, body.get("activeConnections"));
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    @Test
    void deleteDatabase_activeConnections_forceDeleteSucceeds() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.empty());
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of());

        Response response = controller.deleteDatabase(REPO, DB_NAME, PASSWORD, true);
        assertEquals(200, response.getStatus());
        verify(databaseService).dropDatabase(REPO, DB_NAME);
    }

    // ---- deleteBulk ----

    @Test
    void deleteBulk_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.deleteBulk(REPO, PASSWORD, List.of("db1")).getStatus());
    }

    @Test
    void deleteBulk_emptyList_returns400() {
        assertEquals(400, controller.deleteBulk(REPO, PASSWORD, List.of()).getStatus());
    }

    @Test
    void deleteBulk_nullList_returns400() {
        assertEquals(400, controller.deleteBulk(REPO, PASSWORD, null).getStatus());
    }

    @Test
    void deleteBulk_tooMany_returns400() {
        List<String> names = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> "db" + i).toList();
        assertEquals(400, controller.deleteBulk(REPO, PASSWORD, names).getStatus());
    }

    @Test
    void deleteBulk_wrongPassword_returns403() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.deleteBulk(REPO, "wrong", List.of("db1")).getStatus());
    }

    @Test
    void deleteBulk_skipsProtected() {
        ManagedDatabase protectedDb = new ManagedDatabase(REPO, "protected_db");
        protectedDb.setProtectedFlag(true);
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of(protectedDb));

        Response response = controller.deleteBulk(REPO, PASSWORD, List.of("protected_db", "normal_db"));
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(1, body.get("deleted"));
        assertEquals(1, body.get("skipped"));

        verify(databaseService, never()).dropDatabase(REPO, "protected_db");
        verify(databaseService).dropDatabase(REPO, "normal_db");
    }

    // ---- updateDescription ----

    @Test
    void updateDescription_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.updateDescription(REPO, DB_NAME, PASSWORD, Map.of("description", "test")).getStatus());
    }

    @Test
    void updateDescription_wrongPassword_returns403() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.updateDescription(REPO, DB_NAME, "wrong", Map.of("description", "test")).getStatus());
    }

    @Test
    void updateDescription_tooLong_returns400() {
        String longDesc = "x".repeat(501);
        assertEquals(400, controller.updateDescription(REPO, DB_NAME, PASSWORD, Map.of("description", longDesc)).getStatus());
    }

    @Test
    void updateDescription_exactly500_succeeds() {
        String desc = "x".repeat(500);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(new ManagedDatabase(REPO, DB_NAME)));

        Response response = controller.updateDescription(REPO, DB_NAME, PASSWORD, Map.of("description", desc));
        assertEquals(200, response.getStatus());
        verify(managedDatabaseRepository).save(argThat(md -> md.getDescription().equals(desc)));
    }

    @Test
    void updateDescription_success_savesAndInvalidatesCache() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(new ManagedDatabase(REPO, DB_NAME)));

        Response response = controller.updateDescription(REPO, DB_NAME, PASSWORD, Map.of("description", "note"));
        assertEquals(200, response.getStatus());
        verify(managedDatabaseRepository).save(argThat(md -> "note".equals(md.getDescription())));
        verify(listManagedDatabasesUseCase).invalidateCache(REPO);
    }

    @Test
    void updateDescription_newDatabase_createsMetadata() {
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.empty());

        controller.updateDescription(REPO, DB_NAME, PASSWORD, Map.of("description", "new note"));

        verify(managedDatabaseRepository).save(argThat(md ->
                md.getName().equals(DB_NAME) && "new note".equals(md.getDescription())));
    }

    // ---- toggleProtected ----

    @Test
    void toggleProtected_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.toggleProtected(REPO, DB_NAME, PASSWORD).getStatus());
    }

    @Test
    void toggleProtected_wrongPassword_returns403() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.toggleProtected(REPO, DB_NAME, "wrong").getStatus());
    }

    @Test
    void toggleProtected_enablesProtection() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));

        Response response = controller.toggleProtected(REPO, DB_NAME, PASSWORD);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) body.get("protected"));
        verify(listManagedDatabasesUseCase).invalidateCache(REPO);
    }

    @Test
    void toggleProtected_disablesProtection() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        md.setProtectedFlag(true);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));

        Response response = controller.toggleProtected(REPO, DB_NAME, PASSWORD);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertFalse((Boolean) body.get("protected"));
    }

    @Test
    void toggleProtected_enablesProtection_autoDisablesContainers() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));

        ContainerExpiration exp1 = new ContainerExpiration("c1", "full1",
                java.time.Instant.now(), REPO, DB_NAME, true);
        ContainerExpiration exp2 = new ContainerExpiration("c2", "full2",
                java.time.Instant.now(), REPO, DB_NAME, false);
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of(exp1, exp2));

        Response response = controller.toggleProtected(REPO, DB_NAME, PASSWORD);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) body.get("protected"));
        assertEquals(1, body.get("disabledDeletionCount"));
        verify(expirationService).disableDatabaseDeletion("c1");
        verify(expirationService, never()).disableDatabaseDeletion("c2");
    }

    @Test
    void toggleProtected_enablesProtection_noContainers_zeroDisabled() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));
        when(expirationService.findByDatabaseName(DB_NAME)).thenReturn(List.of());

        Response response = controller.toggleProtected(REPO, DB_NAME, PASSWORD);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(0, body.get("disabledDeletionCount"));
    }

    @Test
    void toggleProtected_disablesProtection_doesNotAutoDisable() {
        ManagedDatabase md = new ManagedDatabase(REPO, DB_NAME);
        md.setProtectedFlag(true);
        when(managedDatabaseRepository.find(REPO, DB_NAME)).thenReturn(Optional.of(md));

        controller.toggleProtected(REPO, DB_NAME, PASSWORD);

        verify(expirationService, never()).findByDatabaseName(any());
        verify(expirationService, never()).disableDatabaseDeletion(any());
    }

    // ---- cleanupIdle ----

    @Test
    void cleanupIdle_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.cleanupIdle(REPO, Map.of("password", PASSWORD, "minDays", 30)).getStatus());
    }

    @Test
    void cleanupIdle_wrongPassword_returns403() {
        assertEquals(403, controller.cleanupIdle(REPO, Map.of("password", "wrong", "minDays", 30)).getStatus());
    }

    @Test
    void cleanupIdle_minDaysZero_returns400() {
        assertEquals(400, controller.cleanupIdle(REPO, Map.of("password", PASSWORD, "minDays", 0)).getStatus());
    }

    @Test
    void cleanupIdle_minDaysOver365_returns400() {
        assertEquals(400, controller.cleanupIdle(REPO, Map.of("password", PASSWORD, "minDays", 366)).getStatus());
    }

    @Test
    void cleanupIdle_invalidMinDaysType_returns400() {
        assertEquals(400, controller.cleanupIdle(REPO, Map.of("password", PASSWORD, "minDays", "not_a_number")).getStatus());
    }

    @Test
    void cleanupIdle_success_returns200() {
        when(cleanupIdleDatabasesUseCase.cleanup(REPO, 30)).thenReturn(5);

        Response response = controller.cleanupIdle(REPO, Map.of("password", PASSWORD, "minDays", 30));
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(5, body.get("deleted"));
        verify(listManagedDatabasesUseCase).invalidateCache(REPO);
    }

    // ---- enablePgStatStatements ----

    @Test
    void enablePgStatStatements_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.enablePgStatStatements(REPO, DB_NAME, PASSWORD).getStatus());
    }

    @Test
    void enablePgStatStatements_wrongPassword_returns403() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        assertEquals(403, controller.enablePgStatStatements(REPO, DB_NAME, "wrong").getStatus());
    }

    @Test
    void enablePgStatStatements_enabled_returns200() {
        when(databaseService.enablePgStatStatements(REPO, DB_NAME))
                .thenReturn(DatabaseService.PgssResult.ENABLED);

        Response response = controller.enablePgStatStatements(REPO, DB_NAME, PASSWORD);
        assertEquals(200, response.getStatus());
    }

    @Test
    void enablePgStatStatements_alreadyInstalled_returnsSuccess() {
        when(databaseService.enablePgStatStatements(REPO, DB_NAME))
                .thenReturn(DatabaseService.PgssResult.ALREADY_INSTALLED);

        Response response = controller.enablePgStatStatements(REPO, DB_NAME, PASSWORD);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) body.get("alreadyInstalled"));
    }

    @Test
    void enablePgStatStatements_notAvailable_returns400() {
        when(databaseService.enablePgStatStatements(REPO, DB_NAME))
                .thenReturn(DatabaseService.PgssResult.NOT_AVAILABLE);

        assertEquals(400, controller.enablePgStatStatements(REPO, DB_NAME, PASSWORD).getStatus());
    }

    // ---- getDatabaseDetails ----

    @Test
    void getDatabaseDetails_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.getDatabaseDetails(REPO, DB_NAME).getStatus());
    }

    @Test
    void getDatabaseDetails_invalidRepo_returns400() {
        assertEquals(400, controller.getDatabaseDetails("", DB_NAME).getStatus());
    }

    @Test
    void getDatabaseDetails_invalidName_returns400() {
        assertEquals(400, controller.getDatabaseDetails(REPO, "").getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getDatabaseDetails_partialFailure_returnsAvailableData() {
        when(databaseService.getDatabaseHealth(REPO, DB_NAME)).thenThrow(new RuntimeException("health failed"));
        when(databaseService.getDatabaseActivity(REPO, DB_NAME)).thenReturn(null);
        when(databaseService.getDatabaseTableStats(REPO, DB_NAME)).thenReturn(null);

        Response response = controller.getDatabaseDetails(REPO, DB_NAME);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertFalse(body.containsKey("health"));
    }

    // ---- getServerHealth ----

    @Test
    void getServerHealth_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.getServerHealth(REPO).getStatus());
    }

    @Test
    void getServerHealth_invalidRepo_returns400() {
        assertEquals(400, controller.getServerHealth("").getStatus());
    }

    // ---- getDatabaseHealth ----

    @Test
    void getDatabaseHealth_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.getDatabaseHealth(REPO, DB_NAME).getStatus());
    }

    @Test
    void getDatabaseHealth_invalidName_returns400() {
        assertEquals(400, controller.getDatabaseHealth(REPO, "").getStatus());
    }

    // ---- getDatabaseActivity ----

    @Test
    void getDatabaseActivity_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.getDatabaseActivity(REPO, DB_NAME).getStatus());
    }

    // ---- getDatabaseTableStats ----

    @Test
    void getDatabaseTableStats_disabled_returns404() {
        setField("managedEnabled", false);
        assertEquals(404, controller.getDatabaseTableStats(REPO, DB_NAME).getStatus());
    }

    // ---- executeQuery ----

    @Test
    void executeQuery_featureDisabled_returns404() {
        setField("queryEnabled", false);
        assertEquals(404, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "SELECT 1")).getStatus());
    }

    @Test
    void executeQuery_managedDisabled_returns404() {
        setField("managedEnabled", false);
        setField("queryEnabled", true);
        assertEquals(404, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "SELECT 1")).getStatus());
    }

    @Test
    void executeQuery_emptySql_returns400() {
        setField("queryEnabled", true);
        assertEquals(400, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "")).getStatus());
    }

    @Test
    void executeQuery_multiStatement_returns400() {
        setField("queryEnabled", true);
        assertEquals(400, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "SELECT 1; DROP TABLE x")).getStatus());
    }

    @Test
    void executeQuery_ddlBlocked_returns403() {
        setField("queryEnabled", true);
        when(databaseService.detectQueryType("DROP TABLE users")).thenReturn(DatabaseService.QueryType.DDL);
        assertEquals(403, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "DROP TABLE users")).getStatus());
    }

    @Test
    void executeQuery_writeDisabled_returns403() {
        setField("queryEnabled", true);
        setField("queryWriteEnabled", false);
        when(databaseService.detectQueryType("DELETE FROM users")).thenReturn(DatabaseService.QueryType.WRITE);
        assertEquals(403, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "DELETE FROM users")).getStatus());
    }

    @Test
    void executeQuery_writeNoPassword_returns403() {
        setField("queryEnabled", true);
        setField("queryWriteEnabled", true);
        when(databaseService.detectQueryType("DELETE FROM users")).thenReturn(DatabaseService.QueryType.WRITE);
        when(passwordValidationService.validateOperationsPassword(null)).thenReturn(false);
        assertEquals(403, controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "DELETE FROM users")).getStatus());
    }

    @Test
    void executeQuery_selectSuccess_returns200() {
        setField("queryEnabled", true);
        when(databaseService.detectQueryType("SELECT 1")).thenReturn(DatabaseService.QueryType.SELECT);
        when(databaseService.executeQuery(eq(REPO), eq(DB_NAME), eq("SELECT 1"), eq(0), anyInt(), anyInt(), anyLong()))
                .thenReturn(new DatabaseService.QueryResult(List.of("col"), List.of(List.of((Object) 1)), 0, 100, 1, 5, "SELECT"));

        Response response = controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "SELECT 1"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void executeQuery_writeWithPassword_returns200() {
        setField("queryEnabled", true);
        setField("queryWriteEnabled", true);
        when(databaseService.detectQueryType("DELETE FROM old")).thenReturn(DatabaseService.QueryType.WRITE);
        when(passwordValidationService.validateOperationsPassword(PASSWORD)).thenReturn(true);
        when(databaseService.executeQuery(eq(REPO), eq(DB_NAME), eq("DELETE FROM old"), eq(0), anyInt(), anyInt(), anyLong()))
                .thenReturn(new DatabaseService.QueryResult(List.of("affected_rows"), List.of(List.of((Object) 5)), 0, 1, 1, 10, "WRITE"));

        Response response = controller.executeQuery(REPO, DB_NAME, PASSWORD, Map.of("sql", "DELETE FROM old"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void executeQuery_dbError_returns400() {
        setField("queryEnabled", true);
        when(databaseService.detectQueryType("SELECT bad")).thenReturn(DatabaseService.QueryType.SELECT);
        when(databaseService.executeQuery(eq(REPO), eq(DB_NAME), eq("SELECT bad"), eq(0), anyInt(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("ERROR: column \"bad\" does not exist"));

        Response response = controller.executeQuery(REPO, DB_NAME, null, Map.of("sql", "SELECT bad"));
        assertEquals(400, response.getStatus());
    }

    @Test
    void executeQuery_invalidRepo_returns400() {
        setField("queryEnabled", true);
        assertEquals(400, controller.executeQuery("", DB_NAME, null, Map.of("sql", "SELECT 1")).getStatus());
    }

    // ---- helpers ----

    private ManagedDatabaseInfo makeDb(String name) {
        return new ManagedDatabaseInfo(name, REPO, 1024L, 0, null, null,
                null, false, Instant.now(), null, 0, null, false, null, null, null, null);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = ManagedDatabaseController.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(controller, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
