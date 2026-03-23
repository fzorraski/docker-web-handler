package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.domain.model.RunContainerConfig;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.infrastructure.docker.PortFinder;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import br.com.fzdevx.domain.shared.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import br.com.fzdevx.domain.shared.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.function.Consumer;

@ApplicationScoped
public class RunContainerUseCase {


    private static final double XMX_MEMORY_RATIO = 0.75;
    private static final double XMS_MEMORY_RATIO = 0.25;

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    RegistryService registryService;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    PortFinder portFinder;

    @Inject
    RestoreDumpUseCase restoreDumpUseCase;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    Config config;

    @Inject
    MigrationService migrationService;

    @Inject
    DatabaseService databaseService;

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    ResourceCounterService resourceCounterService;

    private final ConcurrentHashMap<String, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();

    public boolean cancel(String ticket) {
        AtomicBoolean flag = activeRuns.get(ticket);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    public void execute(RunContainerConfig request, Consumer<ContainerEvent> eventSink) {
        execute(request, eventSink, null);
    }

    public void execute(RunContainerConfig request, Consumer<ContainerEvent> eventSink, String ticket) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (ticket != null) {
            activeRuns.put(ticket, cancelled);
        }

        String createdContainerId = null;
        try {
            eventSink.accept(ContainerEvent.info("Validating", "Validating input parameters..."));

            Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
            if (repoError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", repoError.get()));
                return;
            }

            Optional<String> tagError = InputValidator.validateTag(request.getTag());
            if (tagError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", tagError.get()));
                return;
            }

            Optional<String> nameError = InputValidator.validateContainerName(request.getContainerName());
            if (nameError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", nameError.get()));
                return;
            }

            Optional<String> envError = InputValidator.validateEnvVars(request.getEnvVars());
            if (envError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", envError.get()));
                return;
            }

            Optional<String> memError = InputValidator.validateMemoryMb(request.getMemoryMb());
            if (memError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", memError.get()));
                return;
            }

            if (request.getDatabaseName() != null && !request.getDatabaseName().isBlank()) {
                Optional<String> dbError = InputValidator.validateDatabaseName(request.getDatabaseName());
                if (dbError.isPresent()) {
                    eventSink.accept(ContainerEvent.error("Validating", dbError.get()));
                    return;
                }
            }

            if (request.getDumpId() != null && !request.getDumpId().isBlank()) {
                Optional<String> dumpIdError = InputValidator.validateUuid(request.getDumpId());
                if (dumpIdError.isPresent()) {
                    eventSink.accept(ContainerEvent.error("Validating", dumpIdError.get()));
                    return;
                }
            }

            // Validate operations password when destructive operations are requested
            if (request.isDeleteDatabaseOnExpiration() && !request.isOperationsPasswordValidated()) {
                eventSink.accept(ContainerEvent.error("Validating", "Invalid operations password."));
                return;
            }

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Validating", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Validating", "Checking repository permissions..."));

            List<String> allowed = allowedRepositoryResolver.getAllowed();

            if (allowed.isEmpty()) {
                eventSink.accept(ContainerEvent.error("Validating",
                        "No repositories are allowed to run. Configure ALLOWED_RUN_REPOSITORIES."));
                return;
            }

            if (!allowed.contains(request.getRepository())) {
                eventSink.accept(ContainerEvent.error("Validating",
                        "Repository '" + request.getRepository() + "' is not in the allowed list."));
                return;
            }

            String imageRef = registryService.buildFullImageRef(request.getRepository(), request.getTag());
            eventSink.accept(ContainerEvent.info("Validating", "All validations passed."));

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Pulling", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Pulling", "Pulling image " + imageRef + "..."));

            try {
                dockerContainerPort.pullImage(imageRef, request.getRepository(), request.getTag(), eventSink);
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Pulling", "Failed to pull image: " + e.getMessage()));
                return;
            }

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Pulling", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Pulling", "Image pulled successfully."));

            eventSink.accept(ContainerEvent.info("Creating", "Creating container..."));

            CreateContainerResponse container;
            try {
                container = createContainer(imageRef, request, eventSink);
                createdContainerId = container.getId();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Creating", "Failed to create container: " + e.getMessage()));
                return;
            }

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating", "Operation cancelled. Removing created container..."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Creating", "Container created."));

            boolean hasDump = request.getDumpId() != null && !request.getDumpId().isBlank();
            boolean hasSnapshot = request.getSnapshotId() != null && !request.getSnapshotId().isBlank();
            if (hasDump || hasSnapshot) {
                if (cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Restoring", "Operation cancelled."));
                    return;
                }
                eventSink.accept(ContainerEvent.info("Restoring", "Starting restore..."));
                RestoreDumpRequest restoreReq = new RestoreDumpRequest();
                restoreReq.setDumpId(hasDump ? request.getDumpId() : null);
                restoreReq.setSnapshotId(hasSnapshot ? request.getSnapshotId() : null);
                restoreReq.setRepository(request.getRepository());
                restoreReq.setTargetDatabase(request.getDatabaseName());
                restoreReq.setCreateDatabase(request.isCreateDatabase());
                restoreReq.setSelectedOptionalScripts(request.getSelectedOptionalScripts());
                restoreReq.setMigrationMode(request.getMigrationMode());
                restoreReq.setMigrationSql(request.getMigrationSql());
                restoreReq.setMigrationSourceVersion(request.getMigrationSourceVersion());
                restoreReq.setMigrationTargetVersion(request.getMigrationTargetVersion());
                boolean restoreSuccess = restoreDumpUseCase.execute(restoreReq, eventSink);
                if (!restoreSuccess) {
                    return;
                }
            } else if (request.getMigrationMode() != null && !request.getMigrationMode().isBlank()
                    && migrationService.isEnabled()
                    && request.getDatabaseName() != null && !request.getDatabaseName().isBlank()) {
                // Standalone migration on existing database (no restore)
                if (!request.isOperationsPasswordValidated()) {
                    eventSink.accept(ContainerEvent.error("Running Migration", "Invalid operations password."));
                    return;
                }
                if (cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Running Migration", "Operation cancelled."));
                    return;
                }

                String pgImage = databaseService.getContainerImage(request.getRepository());
                DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(request.getRepository());

                boolean migrationOk = migrationService.orchestrateMigration(
                        request.getMigrationMode(), request.getMigrationSql(),
                        request.getMigrationSourceVersion(), request.getMigrationTargetVersion(),
                        request.getRepository(), request.getDatabaseName(), pgImage,
                        pgInfo, eventSink, cancelled);
                if (!migrationOk) {
                    return;
                }
            }

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Starting", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Starting", "Starting container..."));

            try {
                dockerClient.startContainerCmd(container.getId()).exec();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Starting", "Failed to start container: " + e.getMessage()));
                return;
            }

            String expirationMessage = scheduleExpiration(request, container.getId());

            resourceCounterService.increment(ResourceCounterService.CONTAINERS);

            createdContainerId = null; // success — don't clean up
            eventSink.accept(ContainerEvent.success("Complete",
                    "Container started successfully from " + imageRef + expirationMessage));
        } finally {
            if (ticket != null) {
                activeRuns.remove(ticket);
            }
            // Clean up container if it was created but the operation was cancelled or failed after creation
            if (createdContainerId != null && cancelled.get()) {
                try {
                    dockerClient.removeContainerCmd(createdContainerId).withForce(true).exec();
                    eventSink.accept(ContainerEvent.info("Creating",
                            "Container removed due to cancellation."));
                } catch (Exception e) {
                    io.quarkus.logging.Log.warnf("Failed to remove container after cancellation: %s", e.getMessage());
                }
            }
        }
    }

    private CreateContainerResponse createContainer(String imageRef, RunContainerConfig request,
                                                     Consumer<ContainerEvent> eventSink) {
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageRef);

        if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
            createCmd.withName(request.getContainerName().trim());
        }

        HostConfig hostConfig = buildHostConfig(request, eventSink);
        if (hostConfig != null) {
            createCmd.withHostConfig(hostConfig);
        }

        List<Integer> containerPorts = portFinder.getContainerPorts(request.getRepository());
        if (!containerPorts.isEmpty()) {
            createCmd.withExposedPorts(
                    containerPorts.stream().map(ExposedPort::tcp).toList());
        }

        List<String> mergedEnvVars = mergeHiddenEnvVars(request.getRepository(), request.getEnvVars(), request.getMemoryMb());
        if (!mergedEnvVars.isEmpty()) {
            createCmd.withEnv(mergedEnvVars);
        }

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Constants.REPOSITORY_LABEL, request.getRepository());
        if (request.getExtraLabels() != null) {
            labels.putAll(request.getExtraLabels());
        }
        createCmd.withLabels(labels);

        return createCmd.exec();
    }

    private HostConfig buildHostConfig(RunContainerConfig request, Consumer<ContainerEvent> eventSink) {
        List<Integer> containerPorts = portFinder.getContainerPorts(request.getRepository());
        boolean hasMemory = request.getMemoryMb() != null;
        boolean hasPorts = !containerPorts.isEmpty();

        if (!hasMemory && !hasPorts) {
            return null;
        }

        HostConfig hostConfig = HostConfig.newHostConfig();

        if (hasMemory) {
            hostConfig.withMemory(request.getMemoryMb() * 1024 * 1024);
        }

        if (hasPorts) {
            List<Integer> hostPorts = portFinder.findAvailablePorts(containerPorts.size());
            Ports portBindings = new Ports();
            for (int i = 0; i < containerPorts.size(); i++) {
                int containerPort = containerPorts.get(i);
                int hostPort = hostPorts.get(i);
                portBindings.bind(
                        ExposedPort.tcp(containerPort),
                        Ports.Binding.bindPort(hostPort));
                eventSink.accept(ContainerEvent.info("Creating",
                        "Port mapped: " + hostPort + " \u2192 " + containerPort));
            }
            hostConfig.withPortBindings(portBindings);
        }

        return hostConfig;
    }

    private List<String> mergeHiddenEnvVars(String repository, List<String> userEnvVars, Long memoryMb) {
        Map<String, String> envMap = new LinkedHashMap<>();

        if (userEnvVars != null) {
            for (String envVar : userEnvVars) {
                int eq = envVar.indexOf('=');
                if (eq > 0) {
                    envMap.put(envVar.substring(0, eq), envVar.substring(eq + 1));
                }
            }
        }

        String configKey = "repository.hidden-env." + repository;
        Optional<String> hiddenValue = config.getOptionalValue(configKey, String.class);
        if (hiddenValue.isPresent() && !hiddenValue.get().isBlank()) {
            for (String entry : hiddenValue.get().split(",")) {
                String trimmed = entry.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    envMap.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
                }
            }
        }

        if (memoryMb != null) {
            String javaOptsVar = config.getOptionalValue("repository.java-opts-var." + repository, String.class)
                    .orElse(null);
            if (javaOptsVar != null) {
                long xmx = (long) (memoryMb * XMX_MEMORY_RATIO);
                long xms = (long) (memoryMb * XMS_MEMORY_RATIO);
                envMap.put(javaOptsVar, "-Xmx" + xmx + "m -Xms" + xms + "m");
            }
        }

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            result.add(e.getKey() + "=" + e.getValue());
        }
        return result;
    }

    private String scheduleExpiration(RunContainerConfig request, String fullContainerId) {
        Instant expiresInstant = resolveExpiration(request);
        if (expiresInstant == null) {
            return "";
        }

        String shortId = fullContainerId.substring(0, 10);
        expirationService.schedule(shortId, fullContainerId, expiresInstant,
                request.getRepository(), request.getDatabaseName(),
                request.isDeleteDatabaseOnExpiration());

        LocalDateTime ldt = LocalDateTime.ofInstant(expiresInstant, ZoneId.systemDefault());
        return " (expires at " + ldt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ")";
    }

    private Instant resolveExpiration(RunContainerConfig request) {
        Instant maxExpiration = findDbDeletionExpiration(request.getDatabaseName());

        if (request.getExpiresAt() == null || request.getExpiresAt().isBlank()) {
            return maxExpiration;
        }

        LocalDateTime ldt = LocalDateTime.parse(request.getExpiresAt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Instant requested = ldt.atZone(ZoneId.systemDefault()).toInstant();

        if (maxExpiration != null && requested.isAfter(maxExpiration)) {
            return maxExpiration;
        }

        return requested;
    }

    private Instant findDbDeletionExpiration(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) {
            return null;
        }
        for (br.com.fzdevx.domain.model.ContainerExpiration other :
                expirationService.findByDatabaseName(databaseName)) {
            if (other.isDeleteDatabaseOnExpiration()) {
                return other.getExpiresAt();
            }
        }
        return null;
    }
}
