package br.com.fzdevx.controller;

import br.com.fzdevx.model.CreateSnapshotRequest;
import br.com.fzdevx.model.DatabaseSnapshot;
import br.com.fzdevx.service.DatabaseService;
import br.com.fzdevx.service.DumpStorageService;
import br.com.fzdevx.service.SnapshotStorageService;
import br.com.fzdevx.usecase.CreateSnapshotUseCase;
import br.com.fzdevx.util.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/database/snapshots")
public class SnapshotController {

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    DatabaseService databaseService;

    @Inject
    CreateSnapshotUseCase createSnapshotUseCase;

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DatabaseSnapshot> listSnapshots() {
        if (!dumpStorageService.isEnabled()) {
            return Collections.emptyList();
        }
        return snapshotStorageService.findAll();
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

        Optional<DatabaseSnapshot> opt = snapshotStorageService.findById(id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        DatabaseSnapshot snapshot = opt.get();
        File file = snapshotStorageService.getStoredFile(snapshot);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String downloadName = buildDownloadFilename(snapshot) + ".gz";

        return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + downloadName + "\"")
                .header("Content-Length", file.length())
                .build();
    }

    @POST
    @Path("/download-direct")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadDirect(CreateSnapshotRequest request) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled."))
                    .build();
        }

        if (!dumpStorageService.validateOperationsPassword(request.getPassword())) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password."))
                    .build();
        }

        DatabaseSnapshot.Format format;
        try {
            format = DatabaseSnapshot.Format.valueOf(request.getFormat());
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid format."))
                    .build();
        }

        String extension = format == DatabaseSnapshot.Format.CUSTOM ? ".dump" : ".sql";
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .format(Instant.now().atZone(ZoneId.systemDefault()));
        String filename = "snapshot_" + request.getSourceDatabaseName() + "_" + timestamp + extension;

        StreamingOutput stream = output -> {
            try {
                createSnapshotUseCase.executeDownload(request, output, error -> {
                    throw new RuntimeException(error);
                });
            } catch (Exception e) {
                Log.errorf("Download snapshot failed: %s", e.getMessage());
                throw new jakarta.ws.rs.WebApplicationException(e.getMessage(),
                        Response.Status.INTERNAL_SERVER_ERROR);
            }
        };

        return Response.ok(stream, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

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

        if (snapshotStorageService.findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Snapshot not found."))
                    .build();
        }

        snapshotStorageService.deleteSnapshot(id);
        return Response.ok(Map.of("success", true)).build();
    }

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
            if (snapshotStorageService.findById(id).isPresent()) {
                snapshotStorageService.deleteSnapshot(id);
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
        List<DatabaseSnapshot> all = snapshotStorageService.findAll();
        return Map.of(
                "totalBytes", snapshotStorageService.getTotalStorageBytes(),
                "fileCount", all.size(),
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
        List<String> allowed = allowedRunRepositories
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList())
                .orElse(Collections.emptyList());

        return allowed.stream()
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
