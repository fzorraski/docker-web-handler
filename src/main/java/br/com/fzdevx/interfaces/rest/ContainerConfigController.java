package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import br.com.fzdevx.interfaces.rest.dto.Response;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Path("/containers")
@RequiresPermission(Permission.CONTAINERS_VIEW)
public class ContainerConfigController {

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    RegistryService registryService;

    @Inject
    DatabaseService databaseService;

    @Inject
    MigrationService migrationService;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    WebhookService webhookService;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantEntitlements tenantEntitlements;

    @Inject
    br.com.fzdevx.infrastructure.config.RbacSettings rbacSettings;

    @Inject
    RequestStash requestStash;

    @Inject
    jakarta.inject.Provider<io.vertx.core.http.HttpServerRequest> requestProvider;

    @Inject
    @ConfigProperty(name = "app.rate-limit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    @Inject
    @ConfigProperty(name = "container.default-expiration-minutes", defaultValue = "480")
    int defaultExpirationMinutes;

    @Inject
    @ConfigProperty(name = "container.memory-limit.enabled", defaultValue = "false")
    boolean memoryLimitEnabled;

    @Inject
    @ConfigProperty(name = "container.memory-limit.max-mb", defaultValue = "65536")
    long memoryLimitMaxMb;

    @Inject
    @ConfigProperty(name = "ui.locale")
    Optional<String> uiLocale;

    @Inject
    br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    @Inject
    @ConfigProperty(name = "container.terminal.upload.default-path", defaultValue = "/tmp")
    String terminalUploadDefaultPath;

    @Inject
    @ConfigProperty(name = "container.log-rotation.max-size", defaultValue = "10m")
    String logRotationMaxSize;

    @Inject
    @ConfigProperty(name = "container.log-rotation.max-files", defaultValue = "3")
    String logRotationMaxFiles;

    @Inject
    Config config;

    @Inject
    MemoryGuardService memoryGuardService;

    @GET
    @Path("/allowed-repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getAllowedRepositories() {
        return tenantEntitlements.filterRepositories(allowedRepositoryResolver.getAllowed());
    }

    @GET
    @Path("/repository-tags")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryTags(@QueryParam("repository") String repository) {
        Response response = new Response();

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            response.setState(0);
            response.setMessage(repoError.get());
            return response;
        }

        if (!allowedRepositoryResolver.isAllowed(repository)
                || !tenantEntitlements.repositoryAllowed(repository)) {
            response.setState(0);
            response.setMessage("Repository '" + repository + "' is not in the allowed list.");
            return response;
        }

        try {
            List<String> tags = registryService.fetchTags(repository);
            response.setState(1);
            response.setTags(tags);
        } catch (Exception e) {
            response.setState(0);
            Log.errorf("Failed to fetch tags for '%s': %s", repository, e.getMessage());
            response.setMessage("Failed to fetch tags for the requested repository.");
        }

        return response;
    }

    @GET
    @Path("/repository-env-keys")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> getRepositoryEnvKeys(@QueryParam("repository") String repository) {
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Collections.emptyList();
        }
        String configKey = "repository.env-keys." + repository;
        Optional<String> value = config.getOptionalValue(configKey, String.class);
        if (value.isEmpty() || value.get().isBlank()) {
            return Collections.emptyList();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (String entry : value.get().split(",")) {
            String trimmed = entry.trim();
            int eq = trimmed.indexOf('=');
            Map<String, String> pair = new LinkedHashMap<>();
            if (eq >= 0) {
                pair.put("key", trimmed.substring(0, eq));
                pair.put("value", trimmed.substring(eq + 1));
            } else {
                pair.put("key", trimmed);
                pair.put("value", "");
            }
            result.add(pair);
        }
        return result;
    }

    /** Also used by the dump upload/restore flows, which only hold DATABASE permissions. */
    @RequiresPermission({Permission.CONTAINERS_VIEW, Permission.DATABASE_VIEW})
    @GET
    @Path("/default-expiration-minutes")
    @Produces(MediaType.APPLICATION_JSON)
    public int getDefaultExpirationMinutes() {
        return defaultExpirationMinutes;
    }

    @GET
    @Path("/memory-limit-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isMemoryLimitEnabled() {
        return memoryLimitEnabled;
    }

    @GET
    @Path("/memory-limit-max-mb")
    @Produces(MediaType.APPLICATION_JSON)
    public long getMemoryLimitMaxMb(@QueryParam("repository") String repository) {
        if (repository != null && !repository.isBlank()
                && InputValidator.validateRepository(repository).isEmpty()) {
            Optional<Long> perRepo = config.getOptionalValue(
                    "repository.memory-limit.max-mb." + repository, Long.class);
            if (perRepo.isPresent()) {
                return perRepo.get();
            }
        }
        return memoryLimitMaxMb;
    }

    @GET
    @Path("/repository-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getRepositoryConfig(@QueryParam("repository") String repository) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean hasRepo = repository != null && !repository.isBlank()
                && InputValidator.validateRepository(repository).isEmpty();

        result.put("memoryLimitEnabled", hasRepo
                ? config.getOptionalValue("repository.memory-limit.enabled." + repository, Boolean.class)
                        .orElse(memoryLimitEnabled)
                : memoryLimitEnabled);

        result.put("memoryLimitMaxMb", hasRepo
                ? config.getOptionalValue("repository.memory-limit.max-mb." + repository, Long.class)
                        .orElse(memoryLimitMaxMb)
                : memoryLimitMaxMb);

        result.put("defaultExpirationMinutes", hasRepo
                ? config.getOptionalValue("repository.default-expiration-minutes." + repository, Integer.class)
                        .orElse(defaultExpirationMinutes)
                : defaultExpirationMinutes);

        return result;
    }

    @GET
    @Path("/locale")
    @Produces(MediaType.TEXT_PLAIN)
    public String getLocale() {
        return uiLocale.orElse("");
    }

    @GET
    @Path("/database-listing-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isDatabaseListingEnabled() {
        return databaseService.isListingEnabled();
    }

    @GET
    @Path("/deletion-on-expiration-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isDeletionOnExpirationEnabled() {
        return databaseService.isDeletionOnExpirationEnabled();
    }

    /** Also used by the dump restore/snapshot flows, which only hold DATABASE permissions. */
    @RequiresPermission({Permission.CONTAINERS_VIEW, Permission.DATABASE_VIEW})
    @GET
    @Path("/repository-has-databases")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean repositoryHasDatabases(@QueryParam("repository") String repository) {
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return false;
        }
        if (!allowedRepositoryResolver.isAllowed(repository)
                || !tenantEntitlements.databaseAllowed(repository)) {
            return false;
        }
        return databaseService.hasDatabaseConfig(repository);
    }

    /** Feature-flag probe used by container and database flows alike. */
    @RequiresPermission({})
    @GET
    @Path("/migration-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isMigrationEnabled() {
        return migrationService.isEnabled();
    }

    /** Feature-flag probe used by container and database flows alike. */
    @RequiresPermission({})
    @GET
    @Path("/webhook-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isWebhookEnabled() {
        return webhookService.isEnabled();
    }

    /** App-wide feature probe used by the whole UI regardless of role, hence no permission required. */
    @RequiresPermission({})
    @GET
    @Path("/features")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("memoryLimit", memoryLimitEnabled);
        features.put("memoryLimitMaxMb", memoryLimitMaxMb);
        features.put("deletionOnExpiration", databaseService.isDeletionOnExpirationEnabled());
        features.put("databaseListing", databaseService.isListingEnabled());
        features.put("dump", dumpStorageService.isEnabled());
        features.put("migration", migrationService.isEnabled());
        features.put("webhook", webhookService.isEnabled());
        features.put("terminal", runtimeSettings.isTerminalEnabled());
        features.put("defaultExpirationMinutes", defaultExpirationMinutes);
        features.put("uploadPasswordRequired", passwordValidationService.isUploadPasswordRequired());
        features.put("operationsPasswordRequired", passwordValidationService.isOperationsPasswordRequired());
        features.put("memoryGuard", memoryGuardService.isEnabled());
        features.put("schedulingPasswordRequired", passwordValidationService.isSchedulingPasswordRequired());
        features.put("terminalPasswordRequired", passwordValidationService.isTerminalPasswordRequired());
        features.put("terminalUpload", runtimeSettings.isTerminalUploadEnabled());
        features.put("terminalUploadMaxSizeMb", runtimeSettings.getTerminalUploadMaxSizeMb());
        features.put("terminalUploadDefaultPath", terminalUploadDefaultPath);
        features.put("terminalImageUpload", runtimeSettings.isTerminalImageUploadEnabled());
        features.put("terminalAttachmentsPath", runtimeSettings.getTerminalImageUploadPath());
        features.put("terminalImageMaxSizeMb", runtimeSettings.getTerminalImageMaxSizeMb());
        features.put("rbac", rbacSettings.isRbacEnabled());
        return features;
    }

    @RequiresPermission(Permission.TERMINAL_ACCESS)
    @POST
    @Path("/terminal/authorize")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response authorizeTerminal(Map<String, String> body) {
        if (!runtimeSettings.isTerminalEnabled()) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Terminal feature is disabled.")).build();
        }
        String password = body.get("password");
        String containerId = body.get("containerId");
        if (containerId == null || containerId.isBlank()) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Container ID is required.")).build();
        }
        if (InputValidator.validateContainerId(containerId).isPresent()) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid container ID.")).build();
        }
        if (!passwordValidationService.validateTerminalPassword(password)) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid terminal password.")).build();
        }
        // squad isolation: the WebSocket endpoint runs off this user-bound ticket
        containerTenantGuard.requireVisible(containerId);
        // the IP has to be read here: the WebSocket handshake that redeems the ticket
        // cannot report it, and the audit entry is written there
        String ticket = requestStash.stashTerminal(containerId, clientIp());
        return jakarta.ws.rs.core.Response.ok(Map.of("ticket", ticket)).build();
    }

    /** Best-effort client IP; a missing one must never block opening a terminal. */
    private String clientIp() {
        try {
            return AuthController.extractClientIp(requestProvider.get(), trustForwardedHeaders);
        } catch (Exception e) {
            Log.debugf("Could not determine client IP for the terminal ticket: %s", e.getMessage());
            return null;
        }
    }

    /** Validates a capability password; used by container and database flows, so any authenticated user may call it. */
    @RequiresPermission({})
    @POST
    @Path("/validate-operations-password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response validateOperationsPassword(Map<String, String> body) {
        String password = body != null ? body.get("password") : null;
        if (!passwordValidationService.validateOperationsPassword(password)) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }
        return jakarta.ws.rs.core.Response.ok(Map.of("valid", true)).build();
    }

    @GET
    @Path("/migration-preview")
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response previewMigration(
            @QueryParam("repository") String repository,
            @QueryParam("sourceVersion") String sourceVersion,
            @QueryParam("targetVersion") String targetVersion) {

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity(Map.of("error", repoError.get())).build();
        }
        if (!allowedRepositoryResolver.isAllowed(repository)
                || !tenantEntitlements.repositoryAllowed(repository)) {
            return jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of("error", "Repository '" + repository + "' is not in the allowed list.")).build();
        }
        Optional<String> srcError = InputValidator.validateVersion(sourceVersion);
        if (srcError.isPresent()) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity(Map.of("error", srcError.get())).build();
        }
        Optional<String> tgtError = InputValidator.validateVersion(targetVersion);
        if (tgtError.isPresent()) {
            return jakarta.ws.rs.core.Response.status(400)
                    .entity(Map.of("error", tgtError.get())).build();
        }

        MigrationService.MigrationResult result = migrationService.previewMigration(
                repository, sourceVersion, targetVersion);

        if (result == null) {
            return jakarta.ws.rs.core.Response.status(502)
                    .entity(Map.of("error", "Failed to fetch migration SQL from API.")).build();
        }

        long statementCount = result.sql().lines()
                .filter(l -> !l.isBlank() && !l.startsWith("--")).count();

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("sql", result.sql());
        response.put("sourceVersion", result.sourceVersion());
        response.put("targetVersion", result.targetVersion());
        response.put("totalStatements", result.totalStatements() != null ? result.totalStatements() : statementCount);
        response.put("versionsIncluded", result.versionsIncluded());

        return jakarta.ws.rs.core.Response.ok(response).build();
    }

    @GET
    @Path("/migrated-databases")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DatabaseMigrationRecord> getMigratedDatabases() {
        List<DatabaseMigrationRecord> records = migrationService.getMigratedDatabases();
        // actor visibility is its own permission (AUDIT_VIEW); mutating is safe
        // because the repositories hand out fresh objects on every read
        if (!currentUser.hasPermission(br.com.fzdevx.domain.model.auth.Permission.AUDIT_VIEW)) {
            records.forEach(r -> r.setMigratedBy(null));
        }
        return records;
    }

    /** Also used by the dump restore flow, which only holds DATABASE permissions. */
    @RequiresPermission({Permission.CONTAINERS_VIEW, Permission.DATABASE_VIEW})
    @GET
    @Path("/migration-api-available")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isMigrationApiAvailable(@QueryParam("repository") String repository) {
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return false;
        }
        if (!allowedRepositoryResolver.isAllowed(repository)
                || !tenantEntitlements.repositoryAllowed(repository)) {
            return false;
        }
        return migrationService.isApiAvailable(repository);
    }

    /** Also used by the restore/snapshot target pickers, which only hold DATABASE permissions. */
    @RequiresPermission({Permission.CONTAINERS_VIEW, Permission.DATABASE_VIEW})
    @GET
    @Path("/repository-databases")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryDatabases(@QueryParam("repository") String repository) {
        Response response = new Response();

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            response.setState(0);
            response.setMessage(repoError.get());
            return response;
        }

        if (!allowedRepositoryResolver.isAllowed(repository)
                || !tenantEntitlements.databaseAllowed(repository)) {
            response.setState(0);
            response.setMessage("Repository '" + repository + "' is not in the allowed list.");
            return response;
        }

        if (!databaseService.hasDatabaseConfig(repository)) {
            response.setState(0);
            response.setMessage("Database listing is not configured for this repository.");
            return response;
        }

        try {
            List<String> databases = databaseService.listDatabases(repository);
            response.setState(1);
            response.setDatabases(databases);
            databaseService.getDbEnvVar(repository).ifPresent(response::setDbEnvVar);
        } catch (Exception e) {
            response.setState(0);
            Log.errorf("Failed to list databases for '%s': %s", repository, e.getMessage());
            response.setMessage("Failed to list databases for the requested repository.");
        }

        return response;
    }

    @GET
    @Path("/memory-status")
    @Produces(MediaType.APPLICATION_JSON)
    public HostMemoryStatus getMemoryStatus() {
        return memoryGuardService.getStatus();
    }
}
