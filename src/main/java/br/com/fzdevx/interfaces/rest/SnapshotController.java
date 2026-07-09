package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.application.usecase.CreateSnapshotUseCase;
import br.com.fzdevx.interfaces.rest.util.ContentDispositionHelper;
import br.com.fzdevx.domain.shared.DateTimeParser;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/database/snapshots")
@RequiresPermission(Permission.DATABASE_VIEW)
public class SnapshotController {

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    @Inject
    br.com.fzdevx.application.port.TenantRepository tenantRepository;

    /** Creator visibility is its own permission (AUDIT_VIEW); strip it for callers without it. */
    private <T> java.util.List<T> withCreatorVisibility(java.util.List<T> items, java.util.function.BiConsumer<T, String> setter) {
        if (!currentUser.hasPermission(br.com.fzdevx.domain.model.auth.Permission.AUDIT_VIEW)) {
            items.forEach(item -> setter.accept(item, null));
        }
        return items;
    }

    /** Tenant-hidden snapshots are reported as nonexistent. */
    private Optional<DatabaseSnapshot> findVisible(String id) {
        return snapshotStorageService.findById(id)
                .filter(s -> tenantVisibility.canSee(s.getTenantId(), s.getSharedWithTenants()));
    }

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    DatabaseService databaseService;

    @Inject
    CreateSnapshotUseCase createSnapshotUseCase;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DatabaseSnapshot> listSnapshots() {
        if (!dumpStorageService.isEnabled()) {
            return Collections.emptyList();
        }
        List<DatabaseSnapshot> visible = tenantVisibility.visible(
                snapshotStorageService.findAll().stream().filter(s -> !s.isTemporary()).toList(),
                DatabaseSnapshot::getTenantId, DatabaseSnapshot::getSharedWithTenants);
        return withCreatorVisibility(new java.util.ArrayList<>(visible), DatabaseSnapshot::setCreatedBy);
    }

    @GET
    @Path("/download/{id}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadSnapshot(@PathParam("id") String id) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        Optional<DatabaseSnapshot> opt = findVisible(id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        DatabaseSnapshot snapshot = opt.get();
        File file = snapshotStorageService.getStoredFile(snapshot);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        snapshotStorageService.markUsed(id);

        String downloadName = buildDownloadFilename(snapshot) + ".gz";


        return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader(downloadName))
                .header("Content-Length", file.length())
                .build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @DELETE
    @Path("/delete/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSnapshot(@PathParam("id") String id,
                                    @HeaderParam("X-Dump-Password") String password) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled."))
                    .build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get()))
                    .build();
        }

        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password."))
                    .build();
        }

        if (findVisible(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found."))
                    .build();
        }

        snapshotStorageService.deleteSnapshot(id);
        return Response.ok(Map.of("success", true)).build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @DELETE
    @Path("/delete/bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBulk(@HeaderParam("X-Dump-Password") String password, List<String> ids) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled."))
                    .build();
        }

        if (ids == null || ids.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No IDs provided."))
                    .build();
        }

        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password."))
                    .build();
        }

        int deleted = 0;
        for (String id : ids) {
            if (InputValidator.validateUuid(id).isPresent()) continue;
            // tenant-hidden ids are skipped, matching the single-delete 404 behavior
            if (findVisible(id).isPresent()) {
                snapshotStorageService.deleteSnapshot(id);
                deleted++;
            }
        }

        return Response.ok(Map.of("success", true, "deleted", deleted)).build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @PUT
    @Path("/metadata/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateMetadata(@PathParam("id") String id,
                                    @HeaderParam("X-Dump-Password") String password,
                                    Map<String, String> body) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled.")).build();
        }
        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }
        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }
        if (findVisible(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found.")).build();
        }
        String label = body.get("label");
        String description = body.get("description");
        boolean updated = snapshotStorageService.updateMetadata(id, label, description);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found.")).build();
        }
        return Response.ok(Map.of("success", true)).build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @PUT
    @Path("/expiration/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateExpiration(@PathParam("id") String id,
                                      @HeaderParam("X-Dump-Password") String password,
                                      Map<String, String> body) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled.")).build();
        }
        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }
        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        if (findVisible(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found.")).build();
        }
        Instant expiresAt = DateTimeParser.parseExpiresAt(body.get("expiresAt"));
        boolean updated = snapshotStorageService.updateExpiration(id, expiresAt);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found.")).build();
        }
        return Response.ok(Map.of("success", true)).build();
    }

    /**
     * Updates which tenants a snapshot is shared with. The owning tenant can
     * only be changed by TENANTS_VIEW_ALL holders (lets admins adopt legacy snapshots).
     */
    @RequiresPermission(Permission.DATABASE_OPERATE)
    @PUT
    @Path("/sharing/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateSharing(@PathParam("id") String id, Map<String, Object> body) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled.")).build();
        }
        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", uuidError.get())).build();
        }
        if (findVisible(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found.")).build();
        }

        List<String> sharedWithTenants = extractTenantIds(body.get("sharedWithTenants"));
        for (String tenantId : sharedWithTenants) {
            if (tenantRepository.findById(tenantId).isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Unknown tenant: " + tenantId)).build();
            }
        }

        boolean changeOwner = body.containsKey("tenantId")
                && tenantVisibility.bypass() && currentUser.isRbacActive();
        String newTenantId = null;
        if (changeOwner) {
            Object raw = body.get("tenantId");
            newTenantId = raw == null || raw.toString().isBlank() ? null : raw.toString();
            if (newTenantId != null && tenantRepository.findById(newTenantId).isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Unknown tenant: " + newTenantId)).build();
            }
        }

        snapshotStorageService.updateSharing(id, sharedWithTenants, newTenantId, changeOwner);
        return Response.ok(Map.of("success", true)).build();
    }

    private List<String> extractTenantIds(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(java.util.Objects::nonNull)
                .map(item -> item.toString().trim())
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @POST
    @Path("/cleanup-idle")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cleanupIdleSnapshots(Map<String, Object> body) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Feature is disabled.")).build();
        }

        String password = body.get("password") != null ? body.get("password").toString() : "";
        int minDays = body.get("minDays") != null ? ((Number) body.get("minDays")).intValue() : 0;

        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        java.time.temporal.ChronoUnit DAYS = java.time.temporal.ChronoUnit.DAYS;
        Instant cutoff = Instant.now().minus(minDays, DAYS);
        List<DatabaseSnapshot> all = snapshotStorageService.findAll();

        int deleted = 0;
        for (DatabaseSnapshot snap : all) {
            if (!tenantVisibility.canSee(snap.getTenantId(), snap.getSharedWithTenants())) continue;
            Instant reference = snap.getLastUsedAt() != null ? snap.getLastUsedAt() : snap.getCreatedAt();
            if (reference != null && reference.isBefore(cutoff)) {
                snapshotStorageService.deleteSnapshot(snap.getId());
                deleted++;
            }
        }

        return Response.ok(Map.of("success", true, "deleted", deleted)).build();
    }

    @GET
    @Path("/storage-info")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStorageInfo() {
        if (!dumpStorageService.isEnabled()) {
            return Map.of("totalBytes", 0, "fileCount", 0, "maxBytes", 0);
        }
        long count = snapshotStorageService.findAll().stream()
                .filter(s -> !s.isTemporary())
                .count();
        return Map.of(
                "totalBytes", snapshotStorageService.getTotalStorageBytes(),
                "fileCount", count,
                "maxBytes", (long) snapshotStorageService.getMaxSizeMb() * 1024 * 1024
        );
    }

    @GET
    @Path("/repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getSnapshotRepositories() {
        if (!dumpStorageService.isEnabled()) {
            return Collections.emptyList();
        }
        return allowedRepositoryResolver.getAllowed().stream()
                .filter(databaseService::hasDatabaseConfig)
                .toList();
    }

    @GET
    @Path("/active")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> getActiveSnapshots() {
        return createSnapshotUseCase.getActiveSnapshots().stream()
                .map(info -> Map.of(
                        "repository", info.repository(),
                        "sourceDatabaseName", info.sourceDatabaseName()
                ))
                .toList();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @POST
    @Path("/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelSnapshot(Map<String, String> body) {
        String repository = body.get("repository");
        String sourceDatabaseName = body.get("sourceDatabaseName");

        if (repository == null || repository.isBlank()
                || sourceDatabaseName == null || sourceDatabaseName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "repository and sourceDatabaseName are required."))
                    .build();
        }

        boolean cancelled = createSnapshotUseCase.cancel(repository, sourceDatabaseName);
        if (!cancelled) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No active snapshot found."))
                    .build();
        }

        return Response.ok(Map.of("success", true)).build();
    }

    private String buildDownloadFilename(DatabaseSnapshot snapshot) {
        String extension = snapshot.getFormat() == DatabaseSnapshot.Format.CUSTOM ? ".dump" : ".sql";
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(snapshot.getCreatedAt().atZone(ZoneId.systemDefault()));
        return "snapshot_" + snapshot.getSourceDatabaseName() + "_" + timestamp + extension;
    }
}
