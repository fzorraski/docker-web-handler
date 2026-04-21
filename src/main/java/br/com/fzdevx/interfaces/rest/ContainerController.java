package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import br.com.fzdevx.domain.shared.Constants;
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
public class ContainerController {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    MemoryGuardService memoryGuardService;

    @Inject
    ContainerListBroadcaster broadcaster;

    @Inject
    Config config;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerContainer> getContainers() {
        List<Container> dockerContainers = dockerClient.listContainersCmd().withShowAll(true).exec();
        List<DockerContainer> containers = new ArrayList<>();

        String selfId = SelfContainerDetector.findSelfContainerId(dockerContainers);
        for (Container dc : dockerContainers) {
            if (dc.getImage().equals(Constants.DOCKER_WEB_HANDLER_IMAGE)) continue;
            if (dc.getId().equals(selfId)) continue;
            if (dc.getLabels() != null && dc.getLabels().containsKey(RestoreDumpUseCase.EPHEMERAL_LABEL)) continue;

            DockerContainer dockerContainer = new DockerContainer();
            dockerContainer.setContainerId(dc.getId().substring(0, 10));
            dockerContainer.setCommand(dc.getCommand().length() > 15 ? dc.getCommand().substring(0, 15) : dc.getCommand());
            dockerContainer.setCreated(DateFormatter.convertSecondsToDate(dc.getCreated()));
            dockerContainer.setImage(dc.getImage());
            dockerContainer.setNames(dc.getNames()[0].replaceFirst("/", ""));
            dockerContainer.setStatus(dc.getStatus());
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

            Instant expiresAt = expirationService.getExpiresAt(dockerContainer.getContainerId());
            if (dockerContainer.getRepository() == null) {
                String repoFromExpiration = expirationService.getRepository(dockerContainer.getContainerId());
                if (repoFromExpiration != null) {
                    dockerContainer.setRepository(repoFromExpiration);
                }
            }
            if (expiresAt != null) {
                dockerContainer.setExpiresAt(expiresAt.toString());
            }

            String scheduledDbName = expirationService.getDatabaseName(dockerContainer.getContainerId());
            if (scheduledDbName != null) {
                dockerContainer.setDatabaseName(scheduledDbName);
                dockerContainer.setDeleteDatabaseOnExpiration(
                        expirationService.isDeleteDatabaseOnExpiration(dockerContainer.getContainerId()));
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

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/stop")
    public boolean stopContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        try {
            dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            return true;
        } catch (Exception e) {
            Log.errorf("Failed to stop container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return false;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/remove")
    public boolean removeContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        try {
            expirationService.remove(dockerContainer.getContainerId());
            try {
                dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            } catch (Exception ignored) {
            }
            dockerClient.removeContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            return true;
        } catch (Exception e) {
            Log.errorf("Failed to remove container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return false;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/start")
    public Response startContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
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
            dockerClient.startContainerCmd(dockerContainer.getContainerId()).exec();
            broadcaster.notifyChange();
            return Response.ok(Map.of("success", true), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (Exception e) {
            Log.errorf("Failed to start container %s: %s", dockerContainer.getContainerId(), e.getMessage());
            return Response.ok(parseStartError(e.getMessage()), MediaType.APPLICATION_JSON_TYPE).build();
        }
    }

    private static final Pattern PORT_PATTERN = Pattern.compile("Bind for [\\d.]+:(\\d+) failed: port is already allocated");
    private static final Pattern ALREADY_RUNNING_PATTERN = Pattern.compile("already (running|started)");

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

    private Map<String, String> buildPortPaths(String image, ContainerPort[] ports) {
        String imageBase = image.contains(":") ? image.substring(0, image.lastIndexOf(':')) : image;

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
