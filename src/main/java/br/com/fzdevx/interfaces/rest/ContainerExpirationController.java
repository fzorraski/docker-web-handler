package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.DatabaseConflict;
import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.domain.shared.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Path("/containers")
@RequiresPermission(Permission.CONTAINERS_VIEW)
public class ContainerExpirationController {

    @Inject
    DockerClient dockerClient;

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ContainerProtectionService protectionService;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    DatabaseService databaseService;

    /** Also used by the dump restore flow, which only holds DATABASE permissions. */
    @RequiresPermission({Permission.CONTAINERS_VIEW, Permission.DATABASE_VIEW})
    @GET
    @Path("/database-conflicts")
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseConflict getDatabaseConflicts(@QueryParam("databaseName") String databaseName) {
        Optional<String> dbError = InputValidator.validateDatabaseName(databaseName);
        if (dbError.isPresent()) {
            return new DatabaseConflict(null, Collections.emptyList(), null);
        }

        List<ContainerExpiration> expirations = expirationService.findByDatabaseName(databaseName);

        // Check protected flag — use repository from expiration entries if available
        boolean isProtected = false;
        if (!expirations.isEmpty()) {
            String repo = expirations.getFirst().getRepository();
            if (repo != null) {
                isProtected = managedDatabaseRepository.find(repo, databaseName)
                        .map(ManagedDatabase::isProtectedFlag).orElse(false);
            }
        } else {
            // No containers use this database — check all repos for the protected flag
            isProtected = managedDatabaseRepository.findAll().stream()
                    .anyMatch(md -> md.getName().equals(databaseName) && md.isProtectedFlag());
            DatabaseConflict conflict = new DatabaseConflict(null, Collections.emptyList(), null);
            conflict.setProtectedFlag(isProtected);
            return conflict;
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

        DatabaseConflict conflict = new DatabaseConflict(scheduledForDeletionBy, inUseByContainers, expiresAt);
        conflict.setProtectedFlag(isProtected);
        return conflict;
    }

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
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
        if (protectionService.isProtectedContainer(dockerContainer.getContainerId())) {
            return false;
        }
        return expirationService.extendExpiration(dockerContainer.getContainerId(), minutes);
    }

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
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

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
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

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/update-expiration")
    public Response updateExpiration(UpdateExpirationRequest request) {
        if (request == null || InputValidator.validateContainerId(request.containerId).isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid container ID.")).build();
        }

        Instant expiresInstant = null;
        if (request.expiresAt != null && !request.expiresAt.isBlank()) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(request.expiresAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                expiresInstant = ldt.atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid date format.")).build();
            }
            if (expiresInstant.isBefore(Instant.now())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Expiration time must be in the future.")).build();
            }
        }

        // A protected container must never expire — reject any attempt to set one.
        if (expiresInstant != null && protectionService.isProtectedContainer(request.containerId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Container is protected; expiration cannot be set.")).build();
        }

        if (request.deleteDatabaseOnExpiration) {
            // scheduling a database drop needs the dedicated delete permission
            if (!currentUser.hasPermission(Permission.DATABASE_DELETE)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("code", "FORBIDDEN",
                                "message", "You do not have permission to perform this action.")).build();
            }
            if (expiresInstant == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Cannot enable database deletion without expiration.")).build();
            }
            if (!databaseService.isDeletionOnExpirationEnabled()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Database deletion on expiration is not enabled.")).build();
            }
            // Verify the container has a valid database associated
            String dbName = expirationService.getDatabaseName(request.containerId);
            if (dbName == null || dbName.isBlank()
                    || InputValidator.validateDatabaseName(dbName).isPresent()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Container has no database associated.")).build();
            }
            if (passwordValidationService.isOperationsPasswordRequired()
                    && !passwordValidationService.validateOperationsPassword(request.operationsPassword)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Invalid operations password.")).build();
            }
        }

        boolean success = expirationService.updateExpiration(
                request.containerId, expiresInstant, request.deleteDatabaseOnExpiration);

        if (!success) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Container expiration not found.")).build();
        }
        return Response.ok(Map.of("success", true)).build();
    }

    public static class UpdateExpirationRequest {
        public String containerId;
        public String expiresAt;
        public boolean deleteDatabaseOnExpiration;
        public String operationsPassword;
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
