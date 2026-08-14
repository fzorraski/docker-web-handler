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
    br.com.fzdevx.infrastructure.config.DatabaseDeletionPolicy deletionPolicy;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

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
    public DatabaseConflict getDatabaseConflicts(@QueryParam("databaseName") String databaseName,
                                                 @QueryParam("repository") String repository) {
        Optional<String> dbError = InputValidator.validateDatabaseName(databaseName);
        if (dbError.isPresent()) {
            return new DatabaseConflict(null, Collections.emptyList(), null);
        }
        if (repository != null && InputValidator.validateRepository(repository).isPresent()) {
            return new DatabaseConflict(null, Collections.emptyList(), null);
        }

        List<ContainerExpiration> expirations = expirationService.findByDatabaseName(databaseName);

        // Resolve the metadata once. The explicit repository disambiguates same-named
        // databases across repos; without it, fall back to the expiration entries'
        // repository as before (and no ownership can be reported).
        String repo = repository != null ? repository
                : expirations.isEmpty() ? null : expirations.getFirst().getRepository();
        Optional<ManagedDatabase> metadata = repo != null
                ? managedDatabaseRepository.find(repo, databaseName)
                : Optional.empty();
        boolean isProtected = metadata.map(ManagedDatabase::isProtectedFlag).orElse(false);
        boolean createdByMe = metadata.map(md -> deletionPolicy.isCaller(md.getCreatedBy())).orElse(false);

        if (expirations.isEmpty()) {
            if (repo == null) {
                // No containers and no repository given — legacy scan for protection only
                isProtected = managedDatabaseRepository.findAll().stream()
                        .anyMatch(md -> md.getName().equals(databaseName) && md.isProtectedFlag());
            }
            DatabaseConflict conflict = new DatabaseConflict(null, Collections.emptyList(), null);
            conflict.setProtectedFlag(isProtected);
            conflict.setCreatedByMe(createdByMe);
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
        conflict.setCreatedByMe(createdByMe);
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
        if (!containerTenantGuard.canSee(dockerContainer.getContainerId())) {
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
        if (!containerTenantGuard.canSee(dockerContainer.getContainerId())) {
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
        if (!containerTenantGuard.canSee(dockerContainer.getContainerId())) {
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
        containerTenantGuard.requireVisible(request.containerId);

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
            // NEWLY arming a database drop needs the delete permission - full, or
            // delete-own when the caller created the target. A record that is
            // already armed stays editable (deadline changes) by anyone allowed
            // here: the arming was authorized once, and the original armer is
            // preserved, so keeping the flag claims no new destructive right
            if (!expirationService.isDeleteDatabaseOnExpiration(request.containerId)
                    && !deletionPolicy.canDelete(expirationService.getRepository(request.containerId), dbName)) {
                // {error} on purpose: expected refusal, not an RBAC denial
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "You can only delete databases you created.")).build();
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
