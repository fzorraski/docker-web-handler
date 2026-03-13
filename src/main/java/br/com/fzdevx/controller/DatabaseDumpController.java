package br.com.fzdevx.controller;

import br.com.fzdevx.model.DatabaseDump;
import br.com.fzdevx.service.DatabaseService;
import br.com.fzdevx.service.DuplicateDumpException;
import br.com.fzdevx.service.DumpStorageService;
import br.com.fzdevx.usecase.RestoreDumpUseCase;
import br.com.fzdevx.util.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/database/dumps")
public class DatabaseDumpController {

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    DatabaseService databaseService;

    @Inject
    RestoreDumpUseCase restoreDumpUseCase;

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

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

            Instant expiresAt = parseExpiresAt(extractString(form, "expiresAt"));

            try (InputStream is = filePart.getBody(InputStream.class, null)) {
                DatabaseDump dump = dumpStorageService.storeUpload(is, filename, databaseName, version, expiresAt);
                return Response.ok(dump).build();
            }
        } catch (DuplicateDumpException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            Log.errorf("Upload failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Upload failed: " + e.getMessage()))
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

        String downloadName = dump.getOriginalFilename().toLowerCase().endsWith(".gz")
                ? dump.getOriginalFilename()
                : dump.getOriginalFilename() + ".gz";

        return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + downloadName + "\"")
                .header("Content-Length", file.length())
                .build();
    }

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
            if (dumpStorageService.findById(id).isPresent()) {
                dumpStorageService.deleteDump(id);
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
        List<String> allowed = allowedRunRepositories
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList())
                .orElse(Collections.emptyList());

        return allowed.stream()
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

    private String extractString(Map<String, List<InputPart>> form, String key) {
        List<InputPart> parts = form.get(key);
        if (parts == null || parts.isEmpty()) return null;
        try {
            return parts.getFirst().getBodyAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Instant parseExpiresAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDateTime ldt = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
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
