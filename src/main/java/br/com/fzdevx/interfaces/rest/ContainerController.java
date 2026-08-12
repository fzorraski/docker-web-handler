package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.domain.shared.ImageReference;
import br.com.fzdevx.infrastructure.docker.SelfContainerDetector;
import br.com.fzdevx.infrastructure.util.DateFormatter;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.ContainerPort;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Path("/containers")
@RequiresPermission(Permission.CONTAINERS_VIEW)
public class ContainerController {

    @Inject
    DockerClient dockerClient;

    @Inject
    AuditLogger auditLogger;

    @Inject
    br.com.fzdevx.infrastructure.config.CurrentUser currentUser;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ContainerProtectionService protectionService;

    @Inject
    br.com.fzdevx.application.usecase.RunContainerUseCase runContainerUseCase;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerVisibilityService visibilityService;

    @Inject
    MemoryGuardService memoryGuardService;

    @Inject
    ContainerListBroadcaster broadcaster;

    @Inject
    br.com.fzdevx.infrastructure.docker.ContainerTenantGuard containerTenantGuard;

    @Inject
    Config config;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerContainer> getContainers() {
        List<Container> dockerContainers = dockerClient.listContainersCmd().withShowAll(true).exec();
        List<DockerContainer> containers = new ArrayList<>();

        // one store read for all expirations instead of up to four per container
        Map<String, br.com.fzdevx.domain.model.ContainerExpiration> expirations =
                expirationService.snapshotByContainerId();

        String selfId = SelfContainerDetector.findSelfContainerId(dockerContainers);
        for (Container dc : dockerContainers) {
            if (isDockerWebHandlerImage(dc.getImage())) continue;
            if (dc.getId().equals(selfId)) continue;
            // infrastructure containers (e.g. the app's own database sidecar)
            if (visibilityService.isHiddenImage(dc.getImage())) continue;
            if (dc.getLabels() != null && dc.getLabels().containsKey(RestoreDumpUseCase.EPHEMERAL_LABEL)) continue;
            // still being built: the container exists from the moment it is created,
            // but its database restore can run for minutes afterwards and a failure
            // removes it again - showing a half-built "Created" row is misleading
            if (runContainerUseCase.isProvisioning(dc.getId())) continue;
            // squad isolation: containers of other tenants are invisible
            if (dc.getLabels() != null
                    && !tenantVisibility.canSee(dc.getLabels().get(Constants.TENANT_LABEL))) continue;

            DockerContainer dockerContainer = new DockerContainer();
            dockerContainer.setContainerId(dc.getId().substring(0, 10));
            dockerContainer.setCommand(dc.getCommand().length() > 15 ? dc.getCommand().substring(0, 15) : dc.getCommand());
            dockerContainer.setCreated(DateFormatter.convertSecondsToDate(dc.getCreated()));
            dockerContainer.setImage(dc.getImage());
            dockerContainer.setNames(dc.getNames()[0].replaceFirst("/", ""));
            dockerContainer.setStatus(dc.getStatus());
            dockerContainer.setProtectedFlag(protectionService.isProtectedImage(dc.getImage()));
            ContainerPort[] ports = dc.getPorts();
            dockerContainer.setPorts(ports.length > 0 ? Arrays.toString(ports) : "-");
            if (ports.length > 0) {
                Map<String, String> portPaths = buildPortPaths(dc.getImage(), ports);
                if (!portPaths.isEmpty()) {
                    dockerContainer.setPortPaths(portPaths);
                }
            }

            ContainerNetworkSettings netSettings = dc.getNetworkSettings();
            if (netSettings != null && netSettings.getNetworks() != null) {
                netSettings.getNetworks().values().stream()
                        .map(ContainerNetwork::getIpAddress)
                        .filter(ip -> ip != null && !ip.isEmpty())
                        .findFirst()
                        .ifPresent(dockerContainer::setIpAddress);
            }

            if (dc.getLabels() != null && dc.getLabels().containsKey(Constants.REPOSITORY_LABEL)) {
                dockerContainer.setRepository(dc.getLabels().get(Constants.REPOSITORY_LABEL));
            }

            // creator visibility is its own permission (AUDIT_VIEW)
            if (dc.getLabels() != null && currentUser.hasPermission(Permission.AUDIT_VIEW)) {
                dockerContainer.setCreatedBy(dc.getLabels().get(Constants.CREATED_BY_LABEL));
            }

            if (dc.getLabels() != null) {
                dockerContainer.setTenantId(dc.getLabels().get(Constants.TENANT_LABEL));
            }

            br.com.fzdevx.domain.model.ContainerExpiration expiration =
                    expirations.get(dockerContainer.getContainerId());
            if (expiration != null) {
                if (dockerContainer.getRepository() == null && expiration.getRepository() != null) {
                    dockerContainer.setRepository(expiration.getRepository());
                }
                if (expiration.getExpiresAt() != null) {
                    dockerContainer.setExpiresAt(expiration.getExpiresAt().toString());
                }
                if (expiration.getDatabaseName() != null) {
                    dockerContainer.setDatabaseName(expiration.getDatabaseName());
                    dockerContainer.setDeleteDatabaseOnExpiration(expiration.isDeleteDatabaseOnExpiration());
                }
            }

            if (dockerContainer.getRepository() != null) {
                dockerContainer.setUpgradeEnabled(
                        config.getOptionalValue("repository.upgrade-enabled." + dockerContainer.getRepository(), Boolean.class)
                                .orElse(false));
            }

            containers.add(dockerContainer);
        }

        return containers;
    }

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/stop")
    public boolean stopContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        containerTenantGuard.requireVisible(dockerContainer.getContainerId());
        if (protectionService.isProtectedContainer(dockerContainer.getContainerId())) {
            Log.warnf("Refusing to stop protected container %s.", dockerContainer.getContainerId());
            return false;
        }
        try {
            String target = auditTarget(dockerContainer.getContainerId());
            dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            auditLogger.log("CONTAINER_STOP", target, "id=" + dockerContainer.getContainerId());
            return true;
        } catch (Exception e) {
            Log.errorf("Failed to stop container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return false;
        }
    }

    @RequiresPermission(Permission.CONTAINERS_RUN)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/remove")
    public boolean removeContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        containerTenantGuard.requireVisible(dockerContainer.getContainerId());
        if (protectionService.isProtectedContainer(dockerContainer.getContainerId())) {
            Log.warnf("Refusing to remove protected container %s.", dockerContainer.getContainerId());
            return false;
        }
        try {
            // resolve the name before removal so the audit trail can record it
            String target = auditTarget(dockerContainer.getContainerId());
            expirationService.remove(dockerContainer.getContainerId());
            try {
                dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            } catch (Exception ignored) {
            }
            dockerClient.removeContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            auditLogger.log("CONTAINER_REMOVE", target, "id=" + dockerContainer.getContainerId());
            return true;
        } catch (Exception e) {
            Log.errorf("Failed to remove container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return false;
        }
    }

    @RequiresPermission(Permission.CONTAINERS_OPERATE)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/start")
    public Response startContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        containerTenantGuard.requireVisible(dockerContainer.getContainerId());
        String memoryError = memoryGuardService.checkMemoryFor(null);
        if (memoryError != null) {
            HostMemoryStatus status = memoryGuardService.getStatus();
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of(
                            "code", "MEMORY_GUARD",
                            "availableMb", status.availableMb(),
                            "thresholdMb", status.thresholdMb()
                    ))
                    .build();
        }
        try {
            String target = auditTarget(dockerContainer.getContainerId());
            dockerClient.startContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            auditLogger.log("CONTAINER_START", target, "id=" + dockerContainer.getContainerId());
            return Response.ok(Map.of("success", true), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (Exception e) {
            Log.errorf("Failed to start container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return Response.ok(parseStartError(e.getMessage()), MediaType.APPLICATION_JSON_TYPE).build();
        }
    }

    private static final Pattern PORT_PATTERN = Pattern.compile("Bind for [\\d.]+:(\\d+) failed: port is already allocated");
    private static final Pattern ALREADY_RUNNING_PATTERN = Pattern.compile("already (running|started)");

    /**
     * Resolves the human-readable container name for the audit trail, straight
     * from Docker (never the client payload, so it can't be spoofed). Falls
     * back to the id when the container is gone or Docker doesn't answer.
     */
    private String auditTarget(String containerId) {
        try {
            return dockerClient.inspectContainerCmd(containerId).exec().getName().replaceFirst("^/", "");
        } catch (Exception e) {
            return containerId;
        }
    }

    private Map<String, Object> parseStartError(String message) {
        if (message == null) {
            return Map.of("success", false, "error", "START_FAILED");
        }
        Matcher portMatcher = PORT_PATTERN.matcher(message);
        if (portMatcher.find()) {
            return Map.of("success", false, "error", "PORT_ALREADY_ALLOCATED", "detail", portMatcher.group(1));
        }
        if (ALREADY_RUNNING_PATTERN.matcher(message).find()) {
            return Map.of("success", false, "error", "ALREADY_RUNNING");
        }
        return Map.of("success", false, "error", "START_FAILED");
    }

    /**
     * Matches a container image against the app's own image regardless of tag or digest,
     * so the docker-web-handler container (e.g. {@code fabriciozrk/docker-web-handler:0.18})
     * is always filtered out of the listing.
     */
    private static boolean isDockerWebHandlerImage(String image) {
        return Constants.DOCKER_WEB_HANDLER_IMAGE.equals(ImageReference.repository(image));
    }

    private Map<String, String> buildPortPaths(String image, ContainerPort[] ports) {
        String imageBase = ImageReference.repository(image);

        Optional<String> pathsValue = config.getOptionalValue("repository.port-paths." + imageBase, String.class);
        if ((pathsValue.isEmpty() || pathsValue.get().isBlank()) && imageBase.contains("/")) {
            String shortName = imageBase.substring(imageBase.lastIndexOf('/') + 1);
            pathsValue = config.getOptionalValue("repository.port-paths." + shortName, String.class);
        }

        if (pathsValue.isEmpty() || pathsValue.get().isBlank()) {
            return Collections.emptyMap();
        }

        Map<Integer, String> containerPortPaths = new HashMap<>();
        for (String entry : pathsValue.get().split(",")) {
            String trimmed = entry.trim();
            int sep = trimmed.indexOf(':');
            if (sep > 0) {
                try {
                    int port = Integer.parseInt(trimmed.substring(0, sep));
                    String path = trimmed.substring(sep + 1);
                    containerPortPaths.put(port, path);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (containerPortPaths.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (ContainerPort cp : ports) {
            if (cp.getPublicPort() != null && cp.getPrivatePort() != null) {
                String path = containerPortPaths.get(cp.getPrivatePort());
                if (path != null) {
                    result.put(String.valueOf(cp.getPublicPort()), path);
                }
            }
        }
        return result;
    }
}
