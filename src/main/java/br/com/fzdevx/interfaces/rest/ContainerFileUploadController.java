package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.DockerTerminalPort;
import br.com.fzdevx.application.port.DockerTerminalPort.ContainerRuntimeInfo;
import br.com.fzdevx.application.port.DockerTerminalPort.DirectoryCreationException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.domain.shared.ImageSignature;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@jakarta.ws.rs.Path("/containers")
@RequiresPermission(Permission.TERMINAL_ACCESS)
public class ContainerFileUploadController {

    @Inject
    DockerTerminalPort dockerTerminalPort;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

    @Inject
    AuditLogger auditLogger;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Outcome of the shared guards: either a rejection to return, or the container's display name. */
    private record Preflight(Response rejection, String containerName) {}

    @POST
    @jakarta.ws.rs.Path("/{containerId}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadFile(@PathParam("containerId") String containerId, MultipartFormDataInput input) {
        if (!runtimeSettings.isTerminalUploadEnabled()) {
            return error(Response.Status.FORBIDDEN, "File upload to container is disabled.");
        }
        Map<String, List<InputPart>> form = formOf(input);
        Preflight preflight = validateRequest(containerId, form);
        if (preflight.rejection() != null) return preflight.rejection();

        String remotePath = extractString(form, "remotePath");
        Optional<String> pathError = InputValidator.validateContainerPath(remotePath);
        if (pathError.isPresent()) {
            return error(Response.Status.BAD_REQUEST, pathError.get());
        }

        InputPart filePart = filePart(form);
        if (filePart == null) {
            return error(Response.Status.BAD_REQUEST, "No file provided.");
        }
        String filename = extractFilename(filePart);
        if (filename == null || filename.isBlank()) {
            return error(Response.Status.BAD_REQUEST, "Could not determine filename.");
        }
        Optional<String> filenameError = InputValidator.validateUploadFilename(filename);
        if (filenameError.isPresent()) {
            return error(Response.Status.BAD_REQUEST, filenameError.get());
        }

        int maxSizeMb = runtimeSettings.getTerminalUploadMaxSizeMb();
        try (InputStream body = filePart.getBody(InputStream.class, null)) {
            // The destination is user-chosen: it must already exist, never be created as root.
            return stageAndCopy(containerId, preflight.containerName(), body, filename, remotePath, maxSizeMb,
                    false, "TERMINAL_UPLOAD", "File", Map.of());
        } catch (IOException e) {
            Log.errorf("File upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR, "File upload failed. Please try again.");
        }
    }

    /**
     * Uploads a pasted/dropped image as a terminal attachment. The server chooses the filename
     * and destination directory, validates the format by magic bytes, and returns the full path
     * inside the container so the UI can inject it into the prompt.
     */
    @POST
    @jakarta.ws.rs.Path("/{containerId}/upload/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadImage(@PathParam("containerId") String containerId, MultipartFormDataInput input) {
        if (!runtimeSettings.isTerminalImageUploadEnabled()) {
            return error(Response.Status.FORBIDDEN, "Image attachments in the terminal are disabled.");
        }
        Map<String, List<InputPart>> form = formOf(input);
        Preflight preflight = validateRequest(containerId, form);
        if (preflight.rejection() != null) return preflight.rejection();

        String attachmentsPath = runtimeSettings.getTerminalImageUploadPath();
        Optional<String> dirError = InputValidator.validateContainerPath(attachmentsPath);
        if (dirError.isPresent()) {
            Log.errorf("Invalid terminal image upload path '%s': %s", attachmentsPath, dirError.get());
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Attachments path is misconfigured on the server.");
        }

        InputPart filePart = filePart(form);
        if (filePart == null) {
            return error(Response.Status.BAD_REQUEST, "No image provided.");
        }
        int maxSizeMb = runtimeSettings.getTerminalImageMaxSizeMb();

        try (InputStream body = new BufferedInputStream(filePart.getBody(InputStream.class, null))) {
            // Sniff the format before staging so junk is rejected without our own temp file.
            body.mark(ImageSignature.HEADER_LENGTH);
            byte[] header = body.readNBytes(ImageSignature.HEADER_LENGTH);
            body.reset();
            Optional<String> extension = ImageSignature.detectExtension(header);
            if (extension.isEmpty()) {
                return error(Response.Status.BAD_REQUEST, "Unsupported image format. Use PNG, JPEG, GIF, or WebP.");
            }
            String filename = "clip-" + System.currentTimeMillis() + "-" + randomSuffix() + "." + extension.get();
            // The destination is administrator-configured, so creating it on first use is safe.
            return stageAndCopy(containerId, preflight.containerName(), body, filename, attachmentsPath, maxSizeMb,
                    true, "TERMINAL_IMAGE_UPLOAD", "Image", Map.of("path", joinPath(attachmentsPath, filename)));
        } catch (IOException e) {
            Log.errorf("Image upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Image upload failed. Please try again.");
        }
    }

    /**
     * Stages {@code body} to a temp file under the size cap, copies it into the container,
     * logs and audits the upload, and always removes the temp file. Owns every catch clause so
     * both endpoints fail the same way.
     */
    private Response stageAndCopy(String containerId, String containerName, InputStream body, String filename,
                                  String remotePath, int maxSizeMb, boolean createMissingDirectory,
                                  String auditAction, String label, Map<String, String> extraResponse) {
        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("container-upload-");
            tempFile = tempDir.resolve(filename);
            long size = streamToFile(body, tempFile, maxSizeMb);
            if (size < 0) {
                return error(Response.Status.BAD_REQUEST, label + " exceeds the maximum size of " + maxSizeMb + " MB.");
            }

            dockerTerminalPort.copyFileToContainer(containerId, tempFile, remotePath, createMissingDirectory);

            Log.infof("Uploaded %s '%s' (%d bytes) to container '%s' at '%s'",
                    label.toLowerCase(), filename, size, containerId, remotePath);
            audit(auditAction, containerId, containerName, filename, size, remotePath);

            Map<String, String> entity = new LinkedHashMap<>();
            entity.put("filename", filename);
            entity.put("remotePath", remotePath);
            entity.putAll(extraResponse);
            return Response.ok(entity).build();

        } catch (DirectoryCreationException e) {
            Log.errorf("%s upload to container failed: %s", label, e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    "Could not create the destination directory '" + e.getDirectory() + "' inside the container.");
        } catch (Exception e) {
            Log.errorf("%s upload to container failed: %s", label, e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR, label + " upload failed. Please try again.");
        } finally {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
        }
    }

    /**
     * Guards shared by both endpoints. Runs outside the catch-all blocks on purpose: the
     * rate-limit and tenant exceptions must reach {@code GlobalExceptionMapper} (429/403)
     * instead of collapsing into a generic 500.
     */
    private Preflight validateRequest(String containerId, Map<String, List<InputPart>> form) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            return new Preflight(error(Response.Status.BAD_REQUEST, idError.get()), null);
        }
        containerTenantGuard.requireVisible(containerId);

        if (!passwordValidationService.validateTerminalPassword(extractString(form, "password"))) {
            return new Preflight(error(Response.Status.FORBIDDEN, "Invalid terminal password."), null);
        }
        // One inspect gives both the running state and the name the audit entry is filed under.
        ContainerRuntimeInfo info = dockerTerminalPort.inspectContainer(containerId);
        if (!info.running()) {
            return new Preflight(error(Response.Status.BAD_REQUEST, "Container is not running."), null);
        }
        return new Preflight(null, info.name() != null ? info.name() : containerId);
    }

    /** Same shape as TERMINAL_OPEN: the container name as target; the id joins the detail only when it is not already the target. */
    private void audit(String action, String containerId, String containerName, String filename, long size, String remotePath) {
        String facts = "file=" + filename + ", size=" + size + " bytes, path=" + remotePath;
        String detail = containerId.equals(containerName) ? facts : "id=" + containerId + ", " + facts;
        auditLogger.log(action, containerName, detail);
    }

    /**
     * Streams a request body into {@code target}, aborting once it grows past {@code maxSizeMb}.
     *
     * @return the number of bytes written, or {@code -1} when the limit was exceeded
     */
    private static long streamToFile(InputStream body, Path target, int maxSizeMb) throws Exception {
        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        long size = 0;
        try (var out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = body.read(buf)) != -1) {
                size += read;
                if (size > maxBytes) {
                    return -1;
                }
                out.write(buf, 0, read);
            }
        }
        return size;
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    private static Map<String, List<InputPart>> formOf(MultipartFormDataInput input) {
        Map<String, List<InputPart>> form = input.getFormDataMap();
        return form != null ? form : Map.of();
    }

    private static InputPart filePart(Map<String, List<InputPart>> form) {
        List<InputPart> parts = form.get("file");
        return parts == null || parts.isEmpty() ? null : parts.getFirst();
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String joinPath(String dir, String filename) {
        return dir.endsWith("/") ? dir + filename : dir + "/" + filename;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
    }

    private static String extractString(Map<String, List<InputPart>> form, String key) {
        List<InputPart> parts = form.get(key);
        if (parts == null || parts.isEmpty()) return null;
        try {
            return parts.getFirst().getBodyAsString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractFilename(InputPart part) {
        String contentDisposition = part.getHeaders() != null ? part.getHeaders().getFirst("Content-Disposition") : null;
        if (contentDisposition == null) return null;
        for (String s : contentDisposition.split(";")) {
            String trimmed = s.trim();
            if (trimmed.startsWith("filename")) {
                String[] kv = trimmed.split("=", 2);
                return kv.length == 2 ? kv[1].trim().replace("\"", "") : null;
            }
        }
        return null;
    }
}
