package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.DatabaseConflict;
import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.domain.shared.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// ⚠ SOLID — SRP: extracted expiration management endpoints from ContainerController
@Path("/containers")
public class ContainerExpirationController {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerExpirationService expirationService;

    @GET
    @Path("/database-conflicts")
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseConflict getDatabaseConflicts(@QueryParam("databaseName") String databaseName) {
        Optional<String> dbError = InputValidator.validateDatabaseName(databaseName);
        if (dbError.isPresent()) {
            return new DatabaseConflict(null, Collections.emptyList(), null);
        }

        List<ContainerExpiration> expirations = expirationService.findByDatabaseName(databaseName);
        if (expirations.isEmpty()) {
            return new DatabaseConflict(null, Collections.emptyList(), null);
        }

        Map<String, String> containerNames = resolveContainerNames();

        String scheduledForDeletionBy = null;
        String expiresAt = null;
        List<String> inUseByContainers = new ArrayList<>();

        for (ContainerExpiration exp : expirations) {
            String displayName = containerNames.getOrDefault(exp.getShortId(), exp.getShortId());
            if (exp.isDeleteDatabaseOnExpiration()) {
                scheduledForDeletionBy = displayName;
                expiresAt = exp.getExpiresAt().toString();
            }
            inUseByContainers.add(displayName);
        }

        return new DatabaseConflict(scheduledForDeletionBy, inUseByContainers, expiresAt);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/extend-expiration")
    public boolean extendExpiration(DockerContainer dockerContainer,
                                    @QueryParam("minutes") @DefaultValue("10") int minutes) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        if (minutes < 1 || minutes > 1440) {
            return false;
        }
        return expirationService.extendExpiration(dockerContainer.getContainerId(), minutes);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/cancel-db-deletion")
    public boolean cancelDatabaseDeletion(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        return expirationService.disableDatabaseDeletion(dockerContainer.getContainerId());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/cancel-expiration")
    public boolean cancelExpiration(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        expirationService.cancel(dockerContainer.getContainerId());
        return true;
    }

    private Map<String, String> resolveContainerNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (Container dc : dockerClient.listContainersCmd().withShowAll(true).exec()) {
                String shortId = dc.getId().substring(0, 10);
                names.put(shortId, dc.getNames()[0].replaceFirst("/", ""));
            }
        } catch (Exception ignored) {
        }
        return names;
    }
}
