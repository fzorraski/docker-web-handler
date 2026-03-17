package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
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

// ⚠ SOLID — SRP: extracted configuration/settings endpoints from ContainerController
@Path("/containers")
public class ContainerConfigController {

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    RegistryService registryService;

    @Inject
    DatabaseService databaseService;

    @Inject
    @ConfigProperty(name = "container.default-expiration-minutes", defaultValue = "480")
    int defaultExpirationMinutes;

    @Inject
    @ConfigProperty(name = "container.memory-limit.enabled", defaultValue = "false")
    boolean memoryLimitEnabled;

    @Inject
    @ConfigProperty(name = "ui.locale")
    Optional<String> uiLocale;

    @Inject
    Config config;

    @GET
    @Path("/allowed-repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getAllowedRepositories() {
        return allowedRepositoryResolver.getAllowed();
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

        if (!allowedRepositoryResolver.isAllowed(repository)) {
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

    @GET
    @Path("/repository-has-databases")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean repositoryHasDatabases(@QueryParam("repository") String repository) {
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return false;
        }
        if (!allowedRepositoryResolver.isAllowed(repository)) {
            return false;
        }
        return databaseService.hasDatabaseConfig(repository);
    }

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

        if (!allowedRepositoryResolver.isAllowed(repository)) {
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
}
