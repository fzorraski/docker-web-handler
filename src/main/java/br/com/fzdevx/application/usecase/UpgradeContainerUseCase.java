package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.UpgradeContainerRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import br.com.fzdevx.infrastructure.docker.LogRotationResolver;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.docker.PortFinder;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class UpgradeContainerUseCase {

    @Inject DockerClient dockerClient;
    @Inject DockerContainerPort dockerContainerPort;
    @Inject AuditLogger auditLogger;
    @Inject RegistryService registryService;
    @Inject ContainerProtectionService protectionService;
    @Inject ContainerExpirationService expirationService;
    @Inject ExpirationRepository expirationRepository;
    @Inject ContainerSchedulingService schedulingService;
    @Inject PortFinder portFinder;
    @Inject MigrationService migrationService;
    @Inject DatabaseService databaseService;
    @Inject ContainerListBroadcaster broadcaster;
    @Inject org.eclipse.microprofile.config.Config appConfig;
    @Inject LogRotationResolver logRotationResolver;
    @Inject ManagedDatabaseUsageTracker usageTracker;

    private final ConcurrentHashMap<String, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();

    public boolean cancel(String ticket) {
        AtomicBoolean flag = activeRuns.get(ticket);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    public void execute(UpgradeContainerRequest request, Consumer<ContainerEvent> eventSink, String ticket) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (ticket != null) activeRuns.put(ticket, cancelled);
        List<Integer> allocatedPorts = null;

        try {
            // 1. Validate
            eventSink.accept(ContainerEvent.info("Validating", "Validating upgrade request..."));

            if (InputValidator.validateContainerId(request.getContainerId()).isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", "Invalid container ID."));
                return;
            }
            if (!request.hasTagChange() && !request.hasMigration()) {
                eventSink.accept(ContainerEvent.error("Validating", "No tag change or migration configured."));
                return;
            }

            // 2. Inspect old container
            eventSink.accept(ContainerEvent.info("Inspecting", "Reading container configuration..."));
            InspectContainerResponse inspect;
            try {
                inspect = dockerClient.inspectContainerCmd(request.getContainerId()).exec();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Inspecting", "Failed to inspect container: " + e.getMessage()));
                return;
            }

            // A protected container must not be upgraded — upgrade stops and removes it.
            if (protectionService.isProtectedImage(inspect.getConfig().getImage())) {
                eventSink.accept(ContainerEvent.error("Validating",
                        "Container is protected and cannot be upgraded (upgrading would remove it)."));
                return;
            }

            String containerName = inspect.getName().replaceFirst("^/", "");
            String[] envVars = inspect.getConfig().getEnv();
            Long memoryBytes = inspect.getHostConfig().getMemory();
            Map<String, String> labels = inspect.getConfig().getLabels();
            String repository = labels != null ? labels.get(Constants.REPOSITORY_LABEL) : null;

            if (repository == null || repository.isBlank()) {
                eventSink.accept(ContainerEvent.error("Inspecting", "Cannot determine repository from container labels."));
                return;
            }

            boolean upgradeAllowed = appConfig.getOptionalValue(
                    "repository.upgrade-enabled." + repository, Boolean.class).orElse(false);
            if (!upgradeAllowed && request.hasTagChange()) {
                eventSink.accept(ContainerEvent.error("Validating",
                        "Upgrade is not enabled for repository '" + repository + "'."));
                return;
            }

            // Extract old port bindings (containerPort → hostPort) for reuse
            Map<Integer, Integer> oldPortBindings = extractPortBindings(inspect);

            // Read expiration metadata
            String shortId = request.getContainerId();
            ContainerExpiration oldExpiration = expirationRepository.findByContainerId(shortId)
                    .orElse(null);

            String tag = request.hasTagChange() ? request.getNewTag() : extractCurrentTag(inspect);

            eventSink.accept(ContainerEvent.info("Inspecting",
                    "Container: " + containerName + ", repository: " + repository + ", tag: " + tag));

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Inspecting", "Operation cancelled."));
                return;
            }

            // 3. Pull new image (if tag change)
            if (request.hasTagChange()) {
                String imageRef = registryService.buildFullImageRef(repository, tag);
                eventSink.accept(ContainerEvent.info("Pulling", "Pulling image " + imageRef + "..."));
                try {
                    dockerContainerPort.pullImage(imageRef, repository, tag, eventSink);
                } catch (Exception e) {
                    eventSink.accept(ContainerEvent.error("Pulling", "Failed to pull image: " + e.getMessage()));
                    return;
                }
            } else {
                eventSink.accept(ContainerEvent.info("Pulling", "No tag change, skipping image pull."));
            }

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Pulling", "Operation cancelled."));
                return;
            }

            // Migration-only mode: no need to recreate container
            if (!request.hasTagChange() && request.hasMigration()) {
                eventSink.accept(ContainerEvent.info("Running Migration", "Running migration on existing container..."));
                runMigration(request, repository, oldExpiration, eventSink, cancelled);
                eventSink.accept(ContainerEvent.success("Complete",
                        "Migration completed successfully on container '" + containerName + "'."));
                broadcaster.notifyChange();
                return;
            }

            // 4. Stop old container
            eventSink.accept(ContainerEvent.info("Stopping", "Stopping container " + containerName + "..."));
            try {
                dockerClient.stopContainerCmd(inspect.getId()).exec();
            } catch (Exception ignored) {
                // May already be stopped
            }

            // 5. Remove old container — point of no return
            eventSink.accept(ContainerEvent.info("Removing", "Removing old container..."));
            try {
                dockerClient.removeContainerCmd(inspect.getId()).exec();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Removing", "Failed to remove old container: " + e.getMessage()));
                return;
            }

            // Remove old expiration record (metadata will be re-created after new container is created)
            expirationService.remove(shortId);

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating",
                        "Operation cancelled after removing old container. Container '" + containerName + "' was removed."));
                return;
            }

            // 6. Create new container
            eventSink.accept(ContainerEvent.info("Creating", "Creating new container with tag " + tag + "..."));
            String imageRef = registryService.buildFullImageRef(repository, tag);
            List<Integer> containerPorts = portFinder.getContainerPorts(repository);

            CreateContainerResponse newContainer;
            try {
                // Port allocation: prefer old host ports, ordered to match container ports config
                if (!containerPorts.isEmpty()) {
                    List<Integer> preferredHostPorts = new ArrayList<>();
                    for (int cp : containerPorts) {
                        Integer hp = oldPortBindings.get(cp);
                        if (hp != null) {
                            preferredHostPorts.add(hp);
                        }
                    }
                    int startPort = portFinder.getHostPortStart(repository);
                    allocatedPorts = portFinder.findAvailablePortsPreferring(preferredHostPorts, containerPorts.size(), startPort);
                }

                CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageRef);
                createCmd.withName(containerName);

                if (envVars != null && envVars.length > 0) {
                    createCmd.withEnv(envVars);
                }

                if (labels != null && !labels.isEmpty()) {
                    createCmd.withLabels(labels);
                }

                HostConfig hostConfig = buildHostConfig(memoryBytes, containerPorts, allocatedPorts, repository);
                if (hostConfig != null) {
                    createCmd.withHostConfig(hostConfig);
                }

                if (!containerPorts.isEmpty()) {
                    createCmd.withExposedPorts(containerPorts.stream().map(ExposedPort::tcp).toList());
                }

                if (allocatedPorts != null) {
                    for (int i = 0; i < containerPorts.size(); i++) {
                        eventSink.accept(ContainerEvent.info("Creating",
                                "Port mapped: " + allocatedPorts.get(i) + " \u2192 " + containerPorts.get(i)));
                    }
                }

                newContainer = createCmd.exec();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Creating",
                        "Failed to create new container: " + e.getMessage()
                                + ". Old container '" + containerName + "' was removed."));
                return;
            }

            String newFullId = newContainer.getId();
            String newShortId = newFullId.substring(0, 10);

            // 7. Run migration (before starting so the container boots with the correct schema)
            boolean migrationFailed = false;
            if (request.hasMigration()) {
                eventSink.accept(ContainerEvent.info("Running Migration", "Running database migration..."));
                try {
                    boolean ok = runMigration(request, repository, oldExpiration, eventSink, cancelled);
                    if (!ok) migrationFailed = true;
                } catch (Exception e) {
                    migrationFailed = true;
                    Log.warnf("Migration failed during upgrade of container %s: %s",
                            containerName, e.getMessage());
                }
            }

            // 8. Start new container
            eventSink.accept(ContainerEvent.info("Starting", "Starting new container..."));
            try {
                dockerClient.startContainerCmd(newFullId).exec();
            } catch (Exception e) {
                // Try to clean up the created-but-not-started container
                try { dockerClient.removeContainerCmd(newFullId).exec(); } catch (Exception ignored) {}
                eventSink.accept(ContainerEvent.error("Starting",
                        "Failed to start new container: " + e.getMessage()
                                + ". Old container '" + containerName + "' was removed."));
                return;
            }

            // 9. Transfer metadata
            if (oldExpiration != null) {
                String dbName = oldExpiration.getDatabaseName();
                Instant expiresAt = oldExpiration.getExpiresAt();
                boolean deleteDb = oldExpiration.isDeleteDatabaseOnExpiration();
                String repo = oldExpiration.getRepository();

                if (expiresAt != null) {
                    expirationService.schedule(newShortId, newFullId, expiresAt, repo, dbName, deleteDb);
                } else if (dbName != null && !dbName.isBlank()) {
                    expirationService.saveMetadata(newShortId, newFullId, repo, dbName);
                }
            }

            // Mark the underlying database as used regardless of whether an
            // expiration record exists. Containers created without an
            // expiration (legacy / external) carry the DATABASE_NAME_LABEL set
            // by RunContainerUseCase, so we can still discover the dbName.
            String effectiveDbName = oldExpiration != null
                    ? oldExpiration.getDatabaseName()
                    : (labels != null ? labels.get(Constants.DATABASE_NAME_LABEL) : null);
            usageTracker.markUsed(repository, effectiveDbName);

            // Transfer schedules
            schedulingService.transferSchedules(shortId, newShortId);

            String successMsg = "Container '" + containerName + "' upgraded to " + repository + ":" + tag + ".";
            if (migrationFailed) {
                successMsg += " Warning: migration did not complete — you may need to run it manually.";
            }
            auditLogger.log("CONTAINER_UPGRADE", containerName, "image=" + repository + ":" + tag);
            eventSink.accept(ContainerEvent.success("Complete", successMsg));
            broadcaster.notifyChange();

        } catch (Exception e) {
            Log.errorf("Container upgrade failed: %s", e.getMessage());
            eventSink.accept(ContainerEvent.error("Upgrading", "Upgrade failed: " + e.getMessage()));
        } finally {
            if (allocatedPorts != null) {
                portFinder.releasePorts(allocatedPorts);
            }
            if (ticket != null) {
                activeRuns.remove(ticket);
            }
        }
    }

    private boolean runMigration(UpgradeContainerRequest request, String repository,
                                 ContainerExpiration expiration, Consumer<ContainerEvent> eventSink,
                                 AtomicBoolean cancelled) {
        String databaseName = expiration != null ? expiration.getDatabaseName() : null;
        if (databaseName == null || databaseName.isBlank()) {
            eventSink.accept(ContainerEvent.info("Running Migration", "No database associated, skipping migration."));
            return true;
        }
        if (!databaseService.hasDatabaseConfig(repository)) {
            eventSink.accept(ContainerEvent.info("Running Migration", "No database config for repository, skipping migration."));
            return true;
        }

        String pgImage = databaseService.getContainerImage(repository);
        DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(repository);

        return migrationService.orchestrateMigration(
                request.getMigrationMode(), request.getMigrationSql(),
                request.getMigrationSourceVersion(), request.getMigrationTargetVersion(),
                repository, databaseName, pgImage, pgInfo, eventSink, cancelled);
    }

    private HostConfig buildHostConfig(Long memoryBytes, List<Integer> containerPorts,
                                       List<Integer> hostPorts, String repository) {
        boolean hasMemory = memoryBytes != null && memoryBytes > 0;
        boolean hasPorts = !containerPorts.isEmpty() && hostPorts != null;
        boolean hasLogRotation = logRotationResolver.isEnabled(repository);

        if (!hasMemory && !hasPorts && !hasLogRotation) {
            return null;
        }

        HostConfig hostConfig = HostConfig.newHostConfig();
        logRotationResolver.apply(hostConfig, repository);

        if (hasMemory) {
            hostConfig.withMemory(memoryBytes);
        }

        if (hasPorts) {
            Ports portBindings = new Ports();
            for (int i = 0; i < containerPorts.size(); i++) {
                portBindings.bind(
                        ExposedPort.tcp(containerPorts.get(i)),
                        Ports.Binding.bindPort(hostPorts.get(i)));
            }
            hostConfig.withPortBindings(portBindings);
        }

        return hostConfig;
    }

    private Map<Integer, Integer> extractPortBindings(InspectContainerResponse inspect) {
        Map<Integer, Integer> portMap = new LinkedHashMap<>();
        try {
            Ports bindings = inspect.getHostConfig().getPortBindings();
            if (bindings != null && bindings.getBindings() != null) {
                for (Map.Entry<ExposedPort, Ports.Binding[]> entry : bindings.getBindings().entrySet()) {
                    int containerPort = entry.getKey().getPort();
                    if (entry.getValue() != null) {
                        for (Ports.Binding binding : entry.getValue()) {
                            String hostPort = binding.getHostPortSpec();
                            if (hostPort != null && !hostPort.isBlank()) {
                                portMap.put(containerPort, Integer.parseInt(hostPort));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.warnf("Failed to extract port bindings from container: %s", e.getMessage());
        }
        return portMap;
    }

    private String extractCurrentTag(InspectContainerResponse inspect) {
        String image = inspect.getConfig().getImage();
        if (image != null && image.contains(":")) {
            return image.substring(image.lastIndexOf(':') + 1);
        }
        return "latest";
    }
}
