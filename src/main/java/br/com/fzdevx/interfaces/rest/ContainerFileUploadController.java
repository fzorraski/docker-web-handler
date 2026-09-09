package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.DockerTerminalPort;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    @POST
    @jakarta.ws.rs.Path("/{containerId}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadFile(@PathParam("containerId") String containerId, MultipartFormDataInput input) {
        if (!runtimeSettings.isTerminalUploadEnabled()) {
            return error(Response.Status.FORBIDDEN, "File upload to container is disabled.");
        }
        Map<String, List<InputPart>> form = formOf(input);
        Optional<Response> rejected = validateRequest(containerId, form);
        if (rejected.isPresent()) return rejected.get();

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

        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("container-upload-");
            tempFile = tempDir.resolve(filename);
            int maxSizeMb = runtimeSettings.getTerminalUploadMaxSizeMb();
            long size;
            try (InputStream body = filePart.getBody(InputStream.class, null)) {
                size = streamToFile(body, tempFile, maxSizeMb);
            }
            if (size < 0) {
                return error(Response.Status.BAD_REQUEST, "File exceeds the maximum size of " + maxSizeMb + " MB.");
            }

            dockerTerminalPort.copyFileToContainer(containerId, tempFile, remotePath);

            Log.infof("Uploaded file '%s' (%d bytes) to container '%s' at '%s'",
                    filename, size, containerId, remotePath);

            return Response.ok(Map.of(
                    "filename", filename,
                    "remotePath", remotePath
            )).build();

        } catch (DirectoryCreationException e) {
            Log.errorf("File upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    "Could not create the destination directory '" + e.getDirectory() + "' inside the container.");
        } catch (Exception e) {
            Log.errorf("File upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR, "File upload failed. Please try again.");
        } finally {
            deleteQuietly(tempFile);
            deleteQuietly(tempDir);
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
        Optional<Response> rejected = validateRequest(containerId, form);
        if (rejected.isPresent()) return rejected.get();

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

        Path tempDir = null;
        Path tempFile = null;
        try {
            String filename;
            long size;
            try (InputStream body = new BufferedInputStream(filePart.getBody(InputStream.class, null))) {
                // Sniff the format before touching the disk so junk is rejected without a temp file.
                body.mark(ImageSignature.HEADER_LENGTH);
                byte[] header = body.readNBytes(ImageSignature.HEADER_LENGTH);
                body.reset();
                Optional<String> extension = ImageSignature.detectExtension(header);
                if (extension.isEmpty()) {
                    return error(Response.Status.BAD_REQUEST, "Unsupported image format. Use PNG, JPEG, GIF, or WebP.");
                }
                filename = "clip-" + System.currentTimeMillis() + "-" + randomSuffix() + "." + extension.get();
                tempDir = Files.createTempDirectory("container-attachment-");
                tempFile = tempDir.resolve(filename);
                size = streamToFile(body, tempFile, maxSizeMb);
            }
            if (size < 0) {
                return error(Response.Status.BAD_REQUEST, "Image exceeds the maximum size of " + maxSizeMb + " MB.");
            }

            dockerTerminalPort.copyFileToContainer(containerId, tempFile, attachmentsPath);

            String fullPath = joinPath(attachmentsPath, filename);
            Log.infof("Uploaded image attachment '%s' (%d bytes) to container '%s'", fullPath, size, containerId);

            return Response.ok(Map.of(
                    "filename", filename,
                    "remotePath", attachmentsPath,
                    "path", fullPath
            )).build();

        } catch (DirectoryCreationException e) {
            Log.errorf("Image upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR,
                    "Could not create the image directory '" + e.getDirectory() + "' inside the container.");
        } catch (Exception e) {
            Log.errorf("Image upload to container failed: %s", e.getMessage());
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Image upload failed. Please try again.");
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
    private Optional<Response> validateRequest(String containerId, Map<String, List<InputPart>> form) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            return Optional.of(error(Response.Status.BAD_REQUEST, idError.get()));
        }
        containerTenantGuard.requireVisible(containerId);

        if (!passwordValidationService.validateTerminalPassword(extractString(form, "password"))) {
            return Optional.of(error(Response.Status.FORBIDDEN, "Invalid terminal password."));
        }
        if (!dockerTerminalPort.isContainerRunning(containerId)) {
            return Optional.of(error(Response.Status.BAD_REQUEST, "Container is not running."));
        }
        return Optional.empty();
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
