package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.CiCreateEnvironmentRequest;
import br.com.fzdevx.application.dto.CiDestroyResponse;
import br.com.fzdevx.application.dto.CiEnvironmentResponse;
import br.com.fzdevx.application.dto.CiHealthResponse;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class CiEnvironmentUseCase {

    private static final Pattern PORT_MAPPED_PATTERN = Pattern.compile("Port mapped: (\\d+) \u2192 (\\d+)");

    @Inject RunContainerUseCase runContainerUseCase;
    @Inject RemoveContainerUseCase removeContainerUseCase;
    @Inject DockerContainerPort dockerContainerPort;
    @Inject ContainerExpirationService expirationService;
    @Inject DatabaseService databaseService;

    @Inject
    @ConfigProperty(name = "ci.default-ttl-minutes", defaultValue = "120")
    int defaultTtlMinutes;

    @Inject
    @ConfigProperty(name = "ci.max-ttl-minutes", defaultValue = "480")
    int maxTtlMinutes;

    // ---- Create ----

    public CiEnvironmentResponse createEnvironment(CiCreateEnvironmentRequest request) {
        RunContainerRequest runRequest = buildRunRequest(request);

        List<ContainerEvent> events = new ArrayList<>();
        String[] lastError = {null};
        boolean[] succeeded = {false};

        runContainerUseCase.execute(runRequest, event -> {
            events.add(event);
            if (event.getType() == ContainerEvent.EventType.ERROR) lastError[0] = event.getMessage();
            if (event.getType() == ContainerEvent.EventType.SUCCESS) succeeded[0] = true;
        });

        if (!succeeded[0]) {
            throw new InvalidInputException(lastError[0] != null ? lastError[0] : "Environment creation failed.");
        }

        String containerId = findContainerIdByName(runRequest.getContainerName());
        return buildCreateResponse(request, runRequest, containerId, extractPortMappings(events));
    }

    // ---- Destroy ----

    public CiDestroyResponse destroyEnvironment(String containerId, boolean dropDatabase) {
        InputValidator.validateContainerId(containerId)
                .ifPresent(error -> { throw new InvalidInputException(error); });

        String dbName = expirationService.getDatabaseName(containerId);
        String repository = expirationService.getRepository(containerId);

        List<ContainerEvent> events = new ArrayList<>();
        removeContainerUseCase.execute(containerId, events::add);

        events.stream()
                .filter(e -> e.getType() == ContainerEvent.EventType.ERROR)
                .map(ContainerEvent::getMessage)
                .findFirst()
                .ifPresent(msg -> { throw new InvalidInputException(msg); });

        CiDestroyResponse response = new CiDestroyResponse();
        response.setDestroyed(true);
        response.setContainerId(containerId);

        if (dropDatabase && dbName != null && repository != null
                && databaseService.hasDatabaseConfig(repository)) {
            try {
                databaseService.dropDatabase(repository, dbName);
                response.setDatabaseDropped(true);
            } catch (Exception e) {
                response.setDatabaseDropped(false);
                response.setDatabaseDropError(e.getMessage());
            }
        }

        return response;
    }

    // ---- Health ----

    public CiHealthResponse healthCheck(String containerId) {
        InputValidator.validateContainerId(containerId)
                .ifPresent(error -> { throw new InvalidInputException(error); });

        CiHealthResponse health = new CiHealthResponse();
        health.setContainerId(containerId);

        Container container = findContainer(containerId);
        if (container == null) {
            health.setContainerRunning(false);
            health.setContainerStatus("not found");
            health.setMessage("Container not found.");
            return health;
        }

        boolean running = isRunning(container);
        health.setContainerRunning(running);
        health.setContainerStatus(container.getStatus());

        String repository = expirationService.getRepository(containerId);
        String dbName = expirationService.getDatabaseName(containerId);
        if (repository != null && dbName != null && databaseService.hasDatabaseConfig(repository)) {
            try {
                health.setDatabaseAccessible(databaseService.databaseExists(repository, dbName));
            } catch (Exception e) {
                health.setDatabaseAccessible(false);
            }
        }

        health.setMessage(running ? "Healthy" : "Container is not running.");
        return health;
    }

    // ---- List ----

    public List<CiEnvironmentResponse> listEnvironments(String pipelineIdFilter) {
        List<Container> containers = dockerContainerPort.listContainers(true);
        List<CiEnvironmentResponse> result = new ArrayList<>();

        for (Container c : containers) {
            if (c.getLabels() == null || !c.getLabels().containsKey(Constants.CI_LABEL)) continue;

            String pipeline = c.getLabels().get(Constants.CI_PIPELINE_LABEL);
            if (pipelineIdFilter != null && !pipelineIdFilter.isBlank()
                    && !pipelineIdFilter.equals(pipeline)) continue;

            result.add(mapContainerToResponse(c));
        }

        return result;
    }

    // ---- Private helpers ----

    private RunContainerRequest buildRunRequest(CiCreateEnvironmentRequest request) {
        int ttl = request.getTtlMinutes() != null ? request.getTtlMinutes() : defaultTtlMinutes;
        ttl = Math.max(1, Math.min(ttl, maxTtlMinutes));

        RunContainerRequest run = new RunContainerRequest();
        run.setRepository(request.getRepository());
        run.setTag(request.getTag());
        run.setContainerName(request.getEnvironmentName());
        run.setEnvVars(request.getEnvVars());
        run.setExpiresAt(LocalDateTime.now().plusMinutes(ttl).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        run.setMemoryMb(request.getMemoryMb());
        run.setDatabaseName(request.getDatabaseName());
        run.setCreateDatabase(request.isCreateDatabase());
        run.setDumpId(request.getDumpId());
        run.setSnapshotId(request.getSnapshotId());
        run.setDeleteDatabaseOnExpiration(request.isDeleteDatabaseOnExpiration());
        run.setSelectedOptionalScripts(request.getSelectedOptionalScripts());

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Constants.CI_LABEL, "true");
        if (request.getPipelineId() != null && !request.getPipelineId().isBlank()) {
            labels.put(Constants.CI_PIPELINE_LABEL, request.getPipelineId());
        }
        run.setExtraLabels(labels);

        return run;
    }

    private CiEnvironmentResponse buildCreateResponse(CiCreateEnvironmentRequest request,
                                                        RunContainerRequest runRequest,
                                                        String containerId,
                                                        Map<String, String> portMappings) {
        CiEnvironmentResponse response = new CiEnvironmentResponse();
        response.setContainerId(containerId != null ? containerId : "unknown");
        response.setEnvironmentName(request.getEnvironmentName());
        response.setStatus("running");
        response.setPortMappings(portMappings);
        response.setDatabaseName(request.getDatabaseName());
        response.setRepository(request.getRepository());
        response.setTag(request.getTag());
        response.setPipelineId(request.getPipelineId());
        response.setCreatedAt(Instant.now().toString());
        if (runRequest.getExpiresAt() != null) {
            response.setExpiresAt(runRequest.getExpiresAt());
        }
        return response;
    }

    private CiEnvironmentResponse mapContainerToResponse(Container c) {
        String shortId = c.getId().substring(0, Math.min(10, c.getId().length()));

        CiEnvironmentResponse env = new CiEnvironmentResponse();
        env.setContainerId(shortId);
        env.setEnvironmentName(c.getNames() != null && c.getNames().length > 0
                ? c.getNames()[0].replaceFirst("/", "") : shortId);
        env.setStatus(isRunning(c) ? "running" : "stopped");
        env.setRepository(c.getLabels().get(Constants.REPOSITORY_LABEL));
        env.setTag(c.getImage() != null && c.getImage().contains(":")
                ? c.getImage().substring(c.getImage().lastIndexOf(':') + 1) : null);
        env.setPipelineId(c.getLabels().get(Constants.CI_PIPELINE_LABEL));
        env.setDatabaseName(expirationService.getDatabaseName(shortId));

        Instant expiresAt = expirationService.getExpiresAt(shortId);
        if (expiresAt != null) env.setExpiresAt(expiresAt.toString());

        if (c.getPorts() != null) {
            Map<String, String> ports = new LinkedHashMap<>();
            for (ContainerPort p : c.getPorts()) {
                if (p.getPublicPort() != null && p.getPrivatePort() != null) {
                    ports.put(String.valueOf(p.getPrivatePort()), String.valueOf(p.getPublicPort()));
                }
            }
            env.setPortMappings(ports);
        }

        return env;
    }

    private Map<String, String> extractPortMappings(List<ContainerEvent> events) {
        Map<String, String> portMappings = new LinkedHashMap<>();
        for (ContainerEvent event : events) {
            if (event.getMessage() != null) {
                Matcher m = PORT_MAPPED_PATTERN.matcher(event.getMessage());
                if (m.find()) {
                    portMappings.put(m.group(2), m.group(1));
                }
            }
        }
        return portMappings;
    }

    private String findContainerIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Container c : dockerContainerPort.listContainers(true)) {
            if (c.getNames() != null) {
                for (String n : c.getNames()) {
                    if (n.equals("/" + name) || n.equals(name)) {
                        return c.getId().substring(0, Math.min(10, c.getId().length()));
                    }
                }
            }
        }
        return null;
    }

    private Container findContainer(String containerId) {
        for (Container c : dockerContainerPort.listContainers(true)) {
            if (c.getId().startsWith(containerId)) return c;
        }
        return null;
    }

    private static boolean isRunning(Container c) {
        return c.getStatus() != null && c.getStatus().contains("Up");
    }
}
