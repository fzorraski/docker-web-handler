package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.domain.exception.DuplicateDumpException;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.docker.PostRestoreScriptService;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.interfaces.rest.util.ContentDispositionHelper;
import br.com.fzdevx.domain.shared.DateTimeParser;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/database/dumps")
@RequiresPermission(Permission.DATABASE_VIEW)
public class DatabaseDumpController {

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    br.com.fzdevx.application.port.AuditLogger auditLogger;

    @Inject
    DatabaseService databaseService;

    @Inject
    RestoreDumpUseCase restoreDumpUseCase;

    @Inject
    PostRestoreScriptService postRestoreScriptService;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    ResourceCounterService resourceCounterService;

    @GET
    @Path("/enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isEnabled() {
        return dumpStorageService.isEnabled();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DatabaseDump> listDumps() {
        if (!dumpStorageService.isEnabled()) {
            return Collections.emptyList();
        }
        return dumpStorageService.findAll();
    }

    @RequiresPermission(Permission.DATABASE_UPLOAD)
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadDump(MultipartFormDataInput input) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled."))
                    .build();
        }

        try {
            Map<String, List<InputPart>> form = input.getFormDataMap();

            String password = extractString(form, "password");
            if (!dumpStorageService.validateUploadPassword(password)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Invalid upload password."))
                        .build();
            }

            if (dumpStorageService.isStorageFull()) {
                return Response.status(507)
                        .entity(Map.of("error", "Storage quota exceeded. Free up space before uploading."))
                        .build();
            }

            List<InputPart> fileParts = form.get("file");
            if (fileParts == null || fileParts.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "No file provided."))
                        .build();
            }

            InputPart filePart = fileParts.getFirst();
            String filename = extractFilename(filePart);
            if (filename == null || filename.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Could not determine filename."))
                        .build();
            }

            Optional<String> filenameError = InputValidator.validateFilename(filename);
            if (filenameError.isPresent()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", filenameError.get()))
                        .build();
            }

            String databaseName = extractString(form, "databaseName");
            if (databaseName != null && !databaseName.isBlank()) {
                Optional<String> dbError = InputValidator.validateDatabaseName(databaseName);
                if (dbError.isPresent()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", dbError.get()))
                            .build();
                }
            } else {
                databaseName = null;
            }

            String version = extractString(form, "version");
            if (version != null && version.isBlank()) version = null;

            String description = extractString(form, "description");
            if (description != null && description.isBlank()) description = null;

            Instant expiresAt = DateTimeParser.parseExpiresAt(extractString(form, "expiresAt"));

            try (InputStream is = filePart.getBody(InputStream.class, null)) {
                DatabaseDump dump = dumpStorageService.storeUpload(is, filename, databaseName, version, expiresAt, description);
                resourceCounterService.increment(ResourceCounterService.DUMPS);
                return Response.ok(dump).build();
            }
        } catch (DuplicateDumpException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            Log.errorf("Upload failed: %s", e.getMessage());

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Upload failed. Please try again or contact an administrator."))
                    .build();
        }
    }

    @GET
    @Path("/download/{id}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadDump(@PathParam("id") String id) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        Optional<String> uuidError = InputValidator.validateUuid(id);
        if (uuidError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        Optional<DatabaseDump> opt = dumpStorageService.findById(id);
        if (opt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        DatabaseDump dump = opt.get();
        File file = dumpStorageService.getStoredFile(dump);
        if (!file.exists()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        dumpStorageService.markUsed(id);

        String downloadName = dump.getOriginalFilename().toLowerCase().endsWith(".gz")
                ? dump.getOriginalFilename()
                : dump.getOriginalFilename() + ".gz";


        return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader(downloadName))
                .header("Content-Length", file.length())
                .build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @DELETE
    @Path("/delete/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteDump(@PathParam("id") String id,
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

        if (dumpStorageService.findById(id).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Dump not found."))
                    .build();
        }

        dumpStorageService.deleteDump(id);
        auditLogger.log("DUMP_DELETE", id, null);
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
            if (dumpStorageService.findById(id).isPresent()) {
                dumpStorageService.deleteDump(id);
                deleted++;
            }
        }

        if (deleted > 0) {
            auditLogger.log("DUMP_DELETE", deleted + " dump(s)", null);
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
        String version = body.get("version");
        String databaseName = body.get("databaseName");
        if (databaseName != null && !databaseName.isBlank()) {
            Optional<String> dbError = InputValidator.validateDatabaseName(databaseName);
            if (dbError.isPresent()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", dbError.get())).build();
            }
        }
        String description = body.get("description");
        boolean updated = dumpStorageService.updateMetadata(id, version, databaseName, description);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Dump not found.")).build();
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
        Instant expiresAt = DateTimeParser.parseExpiresAt(body.get("expiresAt"));
        boolean updated = dumpStorageService.updateExpiration(id, expiresAt);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Dump not found.")).build();
        }
        return Response.ok(Map.of("success", true)).build();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @POST
    @Path("/cleanup-idle")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cleanupIdleDumps(Map<String, Object> body) {
        if (!dumpStorageService.isEnabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Dump feature is disabled.")).build();
        }

        String password = body.get("password") != null ? body.get("password").toString() : "";
        int minDays = body.get("minDays") != null ? ((Number) body.get("minDays")).intValue() : 0;

        if (!dumpStorageService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        java.time.Instant cutoff = java.time.Instant.now().minus(minDays, java.time.temporal.ChronoUnit.DAYS);
        List<DatabaseDump> all = dumpStorageService.findAll();

        int deleted = 0;
        for (DatabaseDump dump : all) {
            java.time.Instant reference = dump.getLastUsedAt() != null ? dump.getLastUsedAt() : dump.getUploadedAt();
            if (reference != null && reference.isBefore(cutoff)) {
                dumpStorageService.deleteDump(dump.getId());
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
        List<DatabaseDump> all = dumpStorageService.findAll();
        return Map.of(
                "totalBytes", dumpStorageService.getTotalStorageBytes(),
                "fileCount", all.size(),
                "maxBytes", (long) dumpStorageService.getMaxSizeMb() * 1024 * 1024
        );
    }

    @GET
    @Path("/repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getDumpRepositories() {
        if (!dumpStorageService.isEnabled()) {
            return Collections.emptyList();
        }
        return allowedRepositoryResolver.getAllowed().stream()
                .filter(databaseService::hasDatabaseConfig)
                .toList();
    }

    @GET
    @Path("/restore/active")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> getActiveRestores() {
        return restoreDumpUseCase.getActiveRestores().stream()
                .map(info -> Map.of(
                        "repository", info.repository(),
                        "targetDatabase", info.targetDatabase(),
                        "dumpFilename", info.dumpFilename()
                ))
                .toList();
    }

    @RequiresPermission(Permission.DATABASE_OPERATE)
    @POST
    @Path("/restore/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelRestore(Map<String, String> body) {
        String repository = body.get("repository");
        String targetDatabase = body.get("targetDatabase");

        if (repository == null || repository.isBlank() || targetDatabase == null || targetDatabase.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "repository and targetDatabase are required."))
                    .build();
        }

        boolean cancelled = restoreDumpUseCase.cancel(repository, targetDatabase);
        if (!cancelled) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No active restore found."))
                    .build();
        }

        return Response.ok(Map.of("success", true)).build();
    }

    @GET
    @Path("/post-restore-scripts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPostRestoreScripts(@QueryParam("repository") String repository) {
        if (repository == null || repository.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "repository parameter is required."))
                    .build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get()))
                    .build();
        }

        boolean enabled = postRestoreScriptService.isEnabled();
        List<PostRestoreScriptInfo> mandatory = enabled
                ? postRestoreScriptService.discoverMandatoryScripts(repository)
                : List.of();
        List<PostRestoreScriptInfo> optional = enabled
                ? postRestoreScriptService.discoverOptionalScripts(repository)
                : List.of();

        return Response.ok(Map.of(
                "enabled", enabled,
                "mandatory", mandatory,
                "optional", optional,
                "onFailure", postRestoreScriptService.getOnFailure()
        )).build();
    }

    private String extractString(Map<String, List<InputPart>> form, String key) {
        List<InputPart> parts = form.get(key);
        if (parts == null || parts.isEmpty()) return null;
        try {
            return parts.getFirst().getBodyAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }



    private String extractFilename(InputPart part) {
        String[] contentDisposition = part.getHeaders()
                .getFirst("Content-Disposition")
                .split(";");
        for (String s : contentDisposition) {
            String trimmed = s.trim();
            if (trimmed.startsWith("filename")) {
                return trimmed.split("=")[1].trim().replace("\"", "");
            }
        }
        return null;
    }
}
