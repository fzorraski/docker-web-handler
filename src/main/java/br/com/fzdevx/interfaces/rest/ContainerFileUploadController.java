package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.DockerTerminalPort;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Path("/containers")
public class ContainerFileUploadController {

    @Inject
    DockerTerminalPort dockerTerminalPort;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    @ConfigProperty(name = "container.terminal.upload.enabled", defaultValue = "false")
    boolean uploadEnabled;

    @Inject
    @ConfigProperty(name = "container.terminal.upload.max-size-mb", defaultValue = "100")
    int maxSizeMb;

    @POST
    @Path("/{containerId}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadFile(@PathParam("containerId") String containerId, MultipartFormDataInput input) {
        if (!uploadEnabled) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "File upload to container is disabled."))
                    .build();
        }

        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", idError.get()))
                    .build();
        }

        java.nio.file.Path tempFile = null;
        try {
            Map<String, List<InputPart>> form = input.getFormDataMap();

            String password = extractString(form, "password");
            if (!passwordValidationService.validateTerminalPassword(password)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Invalid terminal password."))
                        .build();
            }

            if (!dockerTerminalPort.isContainerRunning(containerId)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Container is not running."))
                        .build();
            }

            String remotePath = extractString(form, "remotePath");
            Optional<String> pathError = InputValidator.validateContainerPath(remotePath);
            if (pathError.isPresent()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", pathError.get()))
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

            Optional<String> filenameError = InputValidator.validateUploadFilename(filename);
            if (filenameError.isPresent()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", filenameError.get()))
                        .build();
            }

            tempFile = java.nio.file.Files.createTempFile("container-upload-", ".tmp");
            long maxBytes = (long) maxSizeMb * 1024 * 1024;
            long size;
            try (InputStream is = filePart.getBody(InputStream.class, null);
                 var out = java.nio.file.Files.newOutputStream(tempFile)) {
                size = 0;
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(Map.of("error", "File exceeds the maximum size of " + maxSizeMb + " MB."))
                                .build();
                    }
                    out.write(buf, 0, read);
                }
            }

            dockerTerminalPort.copyFileToContainer(containerId, tempFile, remotePath);

            Log.infof("Uploaded file '%s' (%d bytes) to container '%s' at '%s'",
                    filename, size, containerId, remotePath);

            return Response.ok(Map.of(
                    "filename", filename,
                    "remotePath", remotePath
            )).build();

        } catch (Exception e) {
            Log.errorf("File upload to container failed: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "File upload failed. Please try again."))
                    .build();
        } finally {
            if (tempFile != null) {
                try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
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
