package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.exception.RateLimitedException;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.interfaces.rest.dto.Response;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerConfigControllerTest {

    @Mock AllowedRepositoryResolver allowedRepositoryResolver;
    @Mock RegistryService registryService;
    @Mock DatabaseService databaseService;
    @Mock MigrationService migrationService;
    @Mock DumpStorageService dumpStorageService;
    @Mock WebhookService webhookService;
    @Mock PasswordValidationService passwordValidationService;
    @Mock br.com.fzdevx.infrastructure.config.RbacSettings rbacSettings;
    @Mock MemoryGuardService memoryGuardService;
    @Mock RequestStash requestStash;
    @Mock Config config;
    @Mock br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    @InjectMocks
    ContainerConfigController controller;

    @BeforeEach
    void setUp() {
        setField("defaultExpirationMinutes", 480);
        setField("memoryLimitEnabled", false);
        setField("memoryLimitMaxMb", 65536L);
        setField("logRotationMaxSize", "10m");
        setField("logRotationMaxFiles", "3");
        setField("uiLocale", Optional.of("en"));
        when(runtimeSettings.isTerminalEnabled()).thenReturn(false);
    }

    private void setField(String name, Object value) {
        try {
            Field f = ContainerConfigController.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(controller, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- getAllowedRepositories ----

    @Test
    void getAllowedRepositories_delegates() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("postgres", "redis"));
        assertEquals(List.of("postgres", "redis"), controller.getAllowedRepositories());
    }

    // ---- getRepositoryTags ----

    @Test
    void getRepositoryTags_invalidRepo_returnsState0() {
        Response res = controller.getRepositoryTags("UPPERCASE");
        assertEquals(0, res.getState());
    }

    @Test
    void getRepositoryTags_notAllowed_returnsState0() {
        when(allowedRepositoryResolver.isAllowed("unknown")).thenReturn(false);
        Response res = controller.getRepositoryTags("unknown");
        assertEquals(0, res.getState());
    }

    @Test
    void getRepositoryTags_success_returnsTagsAndState1() throws Exception {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(registryService.fetchTags("postgres")).thenReturn(List.of("16", "15"));
        Response res = controller.getRepositoryTags("postgres");
        assertEquals(1, res.getState());
        assertEquals(List.of("16", "15"), res.getTags());
    }

    @Test
    void getRepositoryTags_fetchFails_returnsState0() throws Exception {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(registryService.fetchTags("postgres")).thenThrow(new RuntimeException("timeout"));
        Response res = controller.getRepositoryTags("postgres");
        assertEquals(0, res.getState());
    }

    // ---- getRepositoryEnvKeys ----

    @Test
    void getRepositoryEnvKeys_invalidRepo_returnsEmpty() {
        assertTrue(controller.getRepositoryEnvKeys("UPPERCASE").isEmpty());
    }

    @Test
    void getRepositoryEnvKeys_noConfig_returnsEmpty() {
        when(config.getOptionalValue("repository.env-keys.postgres", String.class)).thenReturn(Optional.empty());
        assertTrue(controller.getRepositoryEnvKeys("postgres").isEmpty());
    }

    @Test
    void getRepositoryEnvKeys_parsesKeyValuePairs() {
        when(config.getOptionalValue("repository.env-keys.postgres", String.class))
                .thenReturn(Optional.of("POSTGRES_DB=mydb,POSTGRES_USER=admin"));
        List<Map<String, String>> result = controller.getRepositoryEnvKeys("postgres");
        assertEquals(2, result.size());
        assertEquals("POSTGRES_DB", result.get(0).get("key"));
        assertEquals("mydb", result.get(0).get("value"));
    }

    @Test
    void getRepositoryEnvKeys_keyWithoutValue_setsEmptyValue() {
        when(config.getOptionalValue("repository.env-keys.postgres", String.class))
                .thenReturn(Optional.of("MY_KEY"));
        List<Map<String, String>> result = controller.getRepositoryEnvKeys("postgres");
        assertEquals(1, result.size());
        assertEquals("MY_KEY", result.get(0).get("key"));
        assertEquals("", result.get(0).get("value"));
    }

    // ---- getDefaultExpirationMinutes ----

    @Test
    void getDefaultExpirationMinutes_returnsConfigured() {
        assertEquals(480, controller.getDefaultExpirationMinutes());
    }

    // ---- isMemoryLimitEnabled ----

    @Test
    void isMemoryLimitEnabled_returnsFalse() {
        assertFalse(controller.isMemoryLimitEnabled());
    }

    // ---- getLocale ----

    @Test
    void getLocale_returnsConfigured() {
        assertEquals("en", controller.getLocale());
    }

    // ---- isDatabaseListingEnabled ----

    @Test
    void isDatabaseListingEnabled_delegates() {
        when(databaseService.isListingEnabled()).thenReturn(true);
        assertTrue(controller.isDatabaseListingEnabled());
    }

    // ---- repositoryHasDatabases ----

    @Test
    void repositoryHasDatabases_invalidRepo_returnsFalse() {
        assertFalse(controller.repositoryHasDatabases("UPPERCASE"));
    }

    @Test
    void repositoryHasDatabases_notAllowed_returnsFalse() {
        when(allowedRepositoryResolver.isAllowed("unknown")).thenReturn(false);
        assertFalse(controller.repositoryHasDatabases("unknown"));
    }

    @Test
    void repositoryHasDatabases_hasConfig_returnsTrue() {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("postgres")).thenReturn(true);
        assertTrue(controller.repositoryHasDatabases("postgres"));
    }

    // ---- getRepositoryDatabases ----

    @Test
    void getRepositoryDatabases_invalidRepo_returnsState0() {
        Response res = controller.getRepositoryDatabases("UPPERCASE");
        assertEquals(0, res.getState());
    }

    @Test
    void getRepositoryDatabases_notAllowed_returnsState0() {
        when(allowedRepositoryResolver.isAllowed("unknown")).thenReturn(false);
        Response res = controller.getRepositoryDatabases("unknown");
        assertEquals(0, res.getState());
    }

    @Test
    void getRepositoryDatabases_noConfig_returnsState0() {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("postgres")).thenReturn(false);
        Response res = controller.getRepositoryDatabases("postgres");
        assertEquals(0, res.getState());
    }

    @Test
    void getRepositoryDatabases_success_returnsDbs() {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("postgres")).thenReturn(true);
        when(databaseService.listDatabases("postgres")).thenReturn(List.of("db1", "db2"));
        when(databaseService.getDbEnvVar("postgres")).thenReturn(Optional.of("POSTGRES_DB"));
        Response res = controller.getRepositoryDatabases("postgres");
        assertEquals(1, res.getState());
        assertEquals(List.of("db1", "db2"), res.getDatabases());
        assertEquals("POSTGRES_DB", res.getDbEnvVar());
    }

    // ---- getFeatures ----

    @Test
    void getFeatures_returnsAllFlags() {
        when(databaseService.isDeletionOnExpirationEnabled()).thenReturn(true);
        when(databaseService.isListingEnabled()).thenReturn(true);
        when(dumpStorageService.isEnabled()).thenReturn(true);
        when(migrationService.isEnabled()).thenReturn(false);
        when(webhookService.isEnabled()).thenReturn(false);
        when(passwordValidationService.isUploadPasswordRequired()).thenReturn(true);
        when(passwordValidationService.isOperationsPasswordRequired()).thenReturn(true);
        when(passwordValidationService.isTerminalPasswordRequired()).thenReturn(false);

        Map<String, Object> features = controller.getFeatures();

        assertEquals(false, features.get("memoryLimit"));
        assertEquals(65536L, features.get("memoryLimitMaxMb"));
        assertEquals(true, features.get("deletionOnExpiration"));
        assertEquals(true, features.get("databaseListing"));
        assertEquals(true, features.get("dump"));
        assertEquals(false, features.get("migration"));
        assertEquals(false, features.get("webhook"));
        assertEquals(false, features.get("terminal"));
        assertEquals(480, features.get("defaultExpirationMinutes"));
        assertEquals(true, features.get("uploadPasswordRequired"));
        assertEquals(true, features.get("operationsPasswordRequired"));
        assertEquals(false, features.get("terminalPasswordRequired"));
    }

    // ---- getMemoryLimitMaxMb ----

    @Test
    void getMemoryLimitMaxMb_noRepo_returnsGlobal() {
        assertEquals(65536L, controller.getMemoryLimitMaxMb(null));
    }

    @Test
    void getMemoryLimitMaxMb_noPerRepoConfig_returnsGlobal() {
        when(config.getOptionalValue("repository.memory-limit.max-mb.myapp", Long.class))
                .thenReturn(Optional.empty());
        assertEquals(65536L, controller.getMemoryLimitMaxMb("myapp"));
    }

    @Test
    void getMemoryLimitMaxMb_perRepoConfig_returnsPerRepo() {
        when(config.getOptionalValue("repository.memory-limit.max-mb.myapp", Long.class))
                .thenReturn(Optional.of(1536L));
        assertEquals(1536L, controller.getMemoryLimitMaxMb("myapp"));
    }

    // ---- getRepositoryConfig ----

    @Test
    void getRepositoryConfig_noRepo_returnsGlobalDefaults() {
        Map<String, Object> result = controller.getRepositoryConfig(null);
        assertEquals(false, result.get("memoryLimitEnabled"));
        assertEquals(65536L, result.get("memoryLimitMaxMb"));
        assertEquals(480, result.get("defaultExpirationMinutes"));
    }

    @Test
    void getRepositoryConfig_noOverrides_returnsGlobalDefaults() {
        when(config.getOptionalValue("repository.memory-limit.enabled.myapp", Boolean.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.memory-limit.max-mb.myapp", Long.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.default-expiration-minutes.myapp", Integer.class)).thenReturn(Optional.empty());

        Map<String, Object> result = controller.getRepositoryConfig("myapp");
        assertEquals(false, result.get("memoryLimitEnabled"));
        assertEquals(65536L, result.get("memoryLimitMaxMb"));
        assertEquals(480, result.get("defaultExpirationMinutes"));
    }

    @Test
    void getRepositoryConfig_fullOverrides_returnsPerRepoValues() {
        when(config.getOptionalValue("repository.memory-limit.enabled.myapp", Boolean.class)).thenReturn(Optional.of(true));
        when(config.getOptionalValue("repository.memory-limit.max-mb.myapp", Long.class)).thenReturn(Optional.of(1536L));
        when(config.getOptionalValue("repository.default-expiration-minutes.myapp", Integer.class)).thenReturn(Optional.of(240));

        Map<String, Object> result = controller.getRepositoryConfig("myapp");
        assertEquals(true, result.get("memoryLimitEnabled"));
        assertEquals(1536L, result.get("memoryLimitMaxMb"));
        assertEquals(240, result.get("defaultExpirationMinutes"));
    }

    @Test
    void getRepositoryConfig_partialOverrides_mixesPerRepoAndGlobal() {
        when(config.getOptionalValue("repository.memory-limit.enabled.myapp", Boolean.class)).thenReturn(Optional.of(true));
        when(config.getOptionalValue("repository.memory-limit.max-mb.myapp", Long.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.default-expiration-minutes.myapp", Integer.class)).thenReturn(Optional.of(120));

        Map<String, Object> result = controller.getRepositoryConfig("myapp");
        assertEquals(true, result.get("memoryLimitEnabled"));
        assertEquals(65536L, result.get("memoryLimitMaxMb"));
        assertEquals(120, result.get("defaultExpirationMinutes"));
    }

    // ---- authorizeTerminal ----

    @Test
    void authorizeTerminal_disabled_returnsForbidden() {
        var res = controller.authorizeTerminal(Map.of("containerId", "abc123def4", "password", "x"));
        assertEquals(403, res.getStatus());
    }

    @Test
    void authorizeTerminal_enabled_noContainerId_returnsBadRequest() {
        when(runtimeSettings.isTerminalEnabled()).thenReturn(true);
        var res = controller.authorizeTerminal(Map.of("password", "x"));
        assertEquals(400, res.getStatus());
    }

    @Test
    void authorizeTerminal_enabled_invalidContainerId_returnsBadRequest() {
        when(runtimeSettings.isTerminalEnabled()).thenReturn(true);
        var res = controller.authorizeTerminal(Map.of("containerId", "BAD!", "password", "x"));
        assertEquals(400, res.getStatus());
    }

    @Test
    void authorizeTerminal_enabled_invalidPassword_returnsForbidden() {
        when(runtimeSettings.isTerminalEnabled()).thenReturn(true);
        when(passwordValidationService.validateTerminalPassword("wrong")).thenReturn(false);
        var res = controller.authorizeTerminal(Map.of("containerId", "abc123def4", "password", "wrong"));
        assertEquals(403, res.getStatus());
    }

    @Test
    void authorizeTerminal_valid_returnsTicket() {
        when(runtimeSettings.isTerminalEnabled()).thenReturn(true);
        when(passwordValidationService.validateTerminalPassword("secret")).thenReturn(true);
        when(requestStash.stashTerminal("abc123def4")).thenReturn("ticket-123");
        var res = controller.authorizeTerminal(Map.of("containerId", "abc123def4", "password", "secret"));
        assertEquals(200, res.getStatus());
    }

    // ---- validateOperationsPassword ----

    @Test
    void validateOperationsPassword_invalid_returnsForbidden() {
        when(passwordValidationService.validateOperationsPassword("wrong")).thenReturn(false);
        var res = controller.validateOperationsPassword(Map.of("password", "wrong"));
        assertEquals(403, res.getStatus());
    }

    @Test
    void validateOperationsPassword_valid_returnsOk() {
        when(passwordValidationService.validateOperationsPassword("correct")).thenReturn(true);
        var res = controller.validateOperationsPassword(Map.of("password", "correct"));
        assertEquals(200, res.getStatus());
    }

    @Test
    void validateOperationsPassword_nullBody_returnsForbidden() {
        when(passwordValidationService.validateOperationsPassword(null)).thenReturn(false);
        var res = controller.validateOperationsPassword(null);
        assertEquals(403, res.getStatus());
    }

    @Test
    void validateOperationsPassword_rateLimited_throwsRateLimitedException() {
        when(passwordValidationService.validateOperationsPassword("any"))
                .thenThrow(new RateLimitedException(30));
        assertThrows(RateLimitedException.class,
                () -> controller.validateOperationsPassword(Map.of("password", "any")));
    }

    // ---- isMigrationApiAvailable ----

    @Test
    void isMigrationApiAvailable_invalidRepo_returnsFalse() {
        assertFalse(controller.isMigrationApiAvailable("UPPERCASE"));
    }

    @Test
    void isMigrationApiAvailable_notAllowed_returnsFalse() {
        when(allowedRepositoryResolver.isAllowed("unknown")).thenReturn(false);
        assertFalse(controller.isMigrationApiAvailable("unknown"));
    }

    @Test
    void isMigrationApiAvailable_delegates() {
        when(allowedRepositoryResolver.isAllowed("postgres")).thenReturn(true);
        when(migrationService.isApiAvailable("postgres")).thenReturn(true);
        assertTrue(controller.isMigrationApiAvailable("postgres"));
    }
}
