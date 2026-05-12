package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.domain.model.RunContainerConfig;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.LogRotationResolver;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
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

    @Inject
    MemoryGuardService memoryGuardService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    @ConfigProperty(name = "container.memory-limit.max-mb", defaultValue = "65536")
    long memoryLimitMaxMb;

    @Inject
    LogRotationResolver logRotationResolver;

    static class RunContext {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String restoreRepository;
        volatile String restoreTargetDatabase;
        volatile boolean restoreCreatedDatabase;
        volatile List<Integer> allocatedPorts;
    }

    private final ConcurrentHashMap<String, RunContext> activeRuns = new ConcurrentHashMap<>();

    public boolean cancel(String ticket) {
        RunContext ctx = activeRuns.get(ticket);
        if (ctx == null) return false;
        ctx.cancelled.set(true);
        String repo = ctx.restoreRepository;
        String db = ctx.restoreTargetDatabase;
        if (repo != null && db != null) {
            restoreDumpUseCase.cancel(repo, db);
        }
        return true;
    }

    public void execute(RunContainerConfig request, Consumer<ContainerEvent> eventSink) {
        execute(request, eventSink, null);
    }

    public void execute(RunContainerConfig request, Consumer<ContainerEvent> eventSink, String ticket) {
        Instant startedAt = Instant.now();
        RunContext runCtx = new RunContext();
        if (ticket != null) {
            activeRuns.put(ticket, runCtx);
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

            long effectiveMaxMb = config.getOptionalValue(
                    "repository.memory-limit.max-mb." + request.getRepository(), Long.class)
                    .orElse(memoryLimitMaxMb);
            Optional<String> memError = InputValidator.validateMemoryMb(request.getMemoryMb(), effectiveMaxMb);
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
            if (request.isDeleteDatabaseOnExpiration()
                    && !request.isOperationsPasswordValidated()
                    && !dumpStorageService.validateOperationsPassword(null)) {
                eventSink.accept(ContainerEvent.error("Validating", "Invalid operations password."));
                return;
            }

            if (runCtx.cancelled.get()) {
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

            String memoryError = memoryGuardService.checkMemoryFor(request.getMemoryMb());
            if (memoryError != null) {
                eventSink.accept(ContainerEvent.error("Validating", memoryError));
                return;
            }

            String imageRef = registryService.buildFullImageRef(request.getRepository(), request.getTag());
            eventSink.accept(ContainerEvent.info("Validating", "All validations passed."));

            if (runCtx.cancelled.get()) {
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

            if (runCtx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Pulling", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Pulling", "Image pulled successfully."));

            eventSink.accept(ContainerEvent.info("Creating", "Creating container..."));

            CreateContainerResponse container;
            try {
                container = createContainer(imageRef, request, eventSink, runCtx);
                createdContainerId = container.getId();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Creating", sanitizeCreateError(e.getMessage(), request.getContainerName())));
                return;
            }

            if (runCtx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating", "Operation cancelled. Removing created container..."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Creating", "Container created."));

            boolean hasDump = request.getDumpId() != null && !request.getDumpId().isBlank();
            boolean hasSnapshot = request.getSnapshotId() != null && !request.getSnapshotId().isBlank();
            if (hasDump || hasSnapshot) {
                if (runCtx.cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Preparing", "Operation cancelled."));
                    return;
                }

                boolean dbExistedBefore = true;
                if (request.isCreateDatabase() && request.getDatabaseName() != null
                        && !request.getDatabaseName().isBlank()
                        && databaseService.hasDatabaseConfig(request.getRepository())) {
                    try {
                        dbExistedBefore = databaseService.databaseExists(
                                request.getRepository(), request.getDatabaseName());
                    } catch (Exception e) {
                        io.quarkus.logging.Log.warnf("Failed to check database existence: %s", e.getMessage());
                    }
                }

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

                runCtx.restoreRepository = request.getRepository();
                runCtx.restoreTargetDatabase = request.getDatabaseName();
                boolean restoreSuccess;
                try {
                    restoreSuccess = restoreDumpUseCase.execute(restoreReq, eventSink);
                    if (restoreSuccess && request.isCreateDatabase() && !dbExistedBefore) {
                        runCtx.restoreCreatedDatabase = true;
                    }
                } finally {
                    runCtx.restoreRepository = null;
                    runCtx.restoreTargetDatabase = null;
                }
                if (!restoreSuccess) {
                    return;
                }
            } else if (request.getMigrationMode() != null && !request.getMigrationMode().isBlank()
                    && migrationService.isEnabled()
                    && request.getDatabaseName() != null && !request.getDatabaseName().isBlank()) {
                // Standalone migration on existing database (no restore)
                if (!request.isOperationsPasswordValidated()
                        && !dumpStorageService.validateOperationsPassword(null)) {
                    eventSink.accept(ContainerEvent.error("Running Migration", "Invalid operations password."));
                    return;
                }
                if (runCtx.cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Running Migration", "Operation cancelled."));
                    return;
                }

                String pgImage = databaseService.getContainerImage(request.getRepository());
                DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(request.getRepository());

                boolean migrationOk = migrationService.orchestrateMigration(
                        request.getMigrationMode(), request.getMigrationSql(),
                        request.getMigrationSourceVersion(), request.getMigrationTargetVersion(),
                        request.getRepository(), request.getDatabaseName(), pgImage,
                        pgInfo, eventSink, runCtx.cancelled);
                if (!migrationOk) {
                    return;
                }
            }

            if (runCtx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Starting", "Operation cancelled."));
                return;
            }

            eventSink.accept(ContainerEvent.info("Starting", "Starting container..."));

            try {
                dockerClient.startContainerCmd(container.getId()).exec();
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Starting", sanitizeStartError(e.getMessage())));
                return;
            }

            String expirationMessage = scheduleExpiration(request, container.getId(), startedAt);

            resourceCounterService.increment(ResourceCounterService.CONTAINERS);

            // Track app-level usage for managed databases
            if (request.getDatabaseName() != null && !request.getDatabaseName().isBlank()) {
                try {
                    ManagedDatabase md = managedDatabaseRepository
                            .find(request.getRepository(), request.getDatabaseName())
                            .orElseGet(() -> new ManagedDatabase(request.getRepository(), request.getDatabaseName()));
                    md.setAppLastUsedAt(Instant.now());
                    managedDatabaseRepository.save(md);
                    listManagedDatabasesUseCase.invalidateCache(request.getRepository());
                } catch (Exception ignored) {
                    // Non-critical: don't fail the container start if tracking fails
                }
            }

            createdContainerId = null; // success — don't clean up
            eventSink.accept(ContainerEvent.success("Complete",
                    "Container started successfully from " + imageRef + expirationMessage));
        } finally {
            if (runCtx.allocatedPorts != null) {
                portFinder.releasePorts(runCtx.allocatedPorts);
            }
            if (ticket != null) {
                activeRuns.remove(ticket);
            }
            // Clean up container if it was created but the operation was cancelled or failed
            if (createdContainerId != null) {
                try {
                    dockerClient.removeContainerCmd(createdContainerId).withForce(true).exec();
                    eventSink.accept(ContainerEvent.info("Creating",
                            runCtx.cancelled.get()
                                    ? "Container removed due to cancellation."
                                    : "Container removed after failure."));
                } catch (Exception e) {
                    io.quarkus.logging.Log.warnf("Failed to remove container after %s: %s",
                            runCtx.cancelled.get() ? "cancellation" : "failure", e.getMessage());
                }
            }
            // Drop newly created database if cancelled after restore completed (Scenario B)
            if (runCtx.cancelled.get() && runCtx.restoreCreatedDatabase) {
                try {
                    databaseService.dropDatabase(request.getRepository(), request.getDatabaseName());
                    eventSink.accept(ContainerEvent.info("Restoring",
                            "Dropped newly created database '" + request.getDatabaseName() + "' due to cancellation."));
                } catch (Exception e) {
                    io.quarkus.logging.Log.warnf("Failed to drop database '%s' after cancellation: %s",
                            request.getDatabaseName(), e.getMessage());
                }
            }
        }
    }

    private CreateContainerResponse createContainer(String imageRef, RunContainerConfig request,
                                                     Consumer<ContainerEvent> eventSink, RunContext runCtx) {
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageRef);

        if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
            createCmd.withName(request.getContainerName().trim());
        }

        List<Integer> containerPorts = portFinder.getContainerPorts(request.getRepository());

        HostConfig hostConfig = buildHostConfig(request, eventSink, runCtx, containerPorts);
        if (hostConfig != null) {
            createCmd.withHostConfig(hostConfig);
        }

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

    private HostConfig buildHostConfig(RunContainerConfig request, Consumer<ContainerEvent> eventSink,
                                       RunContext runCtx, List<Integer> containerPorts) {
        boolean hasMemory = request.getMemoryMb() != null;
        boolean hasPorts = !containerPorts.isEmpty();
        String repository = request.getRepository();
        boolean hasLogRotation = logRotationResolver.isEnabled(repository);

        if (!hasMemory && !hasPorts && !hasLogRotation) {
            return null;
        }

        HostConfig hostConfig = HostConfig.newHostConfig();
        logRotationResolver.apply(hostConfig, repository);

        if (hasMemory) {
            hostConfig.withMemory(request.getMemoryMb() * 1024 * 1024);
        }

        if (hasPorts) {
            int startPort = portFinder.getHostPortStart(repository);
            List<Integer> hostPorts = portFinder.findAvailablePorts(containerPorts.size(), startPort);
            runCtx.allocatedPorts = List.copyOf(hostPorts);
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
                String existing = envMap.get(javaOptsVar);
                if (existing != null && !existing.isBlank()) {
                    existing = existing.replaceAll("-Xmx\\S+", "").replaceAll("-Xms\\S+", "")
                            .replaceAll("\\s+", " ").trim();
                    envMap.put(javaOptsVar, "-Xmx" + xmx + "m -Xms" + xms + "m " + existing);
                } else {
                    envMap.put(javaOptsVar, "-Xmx" + xmx + "m -Xms" + xms + "m");
                }
            }
        }

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            result.add(e.getKey() + "=" + e.getValue());
        }
        return result;
    }

    private String scheduleExpiration(RunContainerConfig request, String fullContainerId, Instant startedAt) {
        Instant expiresInstant = resolveExpiration(request, startedAt);
        if (expiresInstant == null) {
            String dbName = request.getDatabaseName();
            if (dbName != null && !dbName.isBlank()) {
                String shortId = fullContainerId.substring(0, 10);
                expirationService.saveMetadata(shortId, fullContainerId, request.getRepository(), dbName);
            }
            return "";
        }

        String shortId = fullContainerId.substring(0, 10);
        expirationService.schedule(shortId, fullContainerId, expiresInstant,
                request.getRepository(), request.getDatabaseName(),
                request.isDeleteDatabaseOnExpiration());

        LocalDateTime ldt = LocalDateTime.ofInstant(expiresInstant, ZoneId.systemDefault());
        return " (expires at " + ldt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ")";
    }

    Instant resolveExpiration(RunContainerConfig request, Instant startedAt) {
        Instant maxExpiration = findDbDeletionExpiration(request.getDatabaseName());

        if (request.getExpiresAt() == null || request.getExpiresAt().isBlank()) {
            return maxExpiration;
        }

        LocalDateTime ldt = LocalDateTime.parse(request.getExpiresAt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Instant requested = ldt.atZone(ZoneId.systemDefault()).toInstant();

        // If the requested expiration is already in the past (e.g. a database restore
        // took longer than the expiration duration), recalculate from now preserving
        // the original duration the user intended.
        if (requested.isBefore(Instant.now())) {
            Duration originalDuration = Duration.between(startedAt, requested);
            requested = Instant.now().plus(originalDuration);
        }

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

    private String sanitizeStartError(String message) {
        if (message == null) {
            return "Failed to start container.";
        }
        if (message.contains("already in use") || message.contains("already allocated")) {
            return "Port binding conflict — a port is already in use. Please try again.";
        }
        if (message.contains("driver failed programming external connectivity")) {
            return "Failed to bind ports — a network conflict occurred. Please try again.";
        }
        return "Failed to start container: " + stripDockerJsonNoise(message);
    }

    private String sanitizeCreateError(String message, String containerName) {
        if (message != null && message.contains("is already in use by container")) {
            return "A container named \"" + containerName + "\" already exists. Remove or rename it first.";
        }
        return "Failed to create container" + (message != null ? ": " + stripDockerJsonNoise(message) : ".");
    }

    /**
     * Strips Docker API JSON wrapping (e.g. {@code Status 500: {"message":"actual error"}})
     * and returns just the inner message.
     */
    private String stripDockerJsonNoise(String message) {
        if (message != null && message.contains("{\"message\":\"")) {
            int start = message.indexOf("{\"message\":\"") + 12;
            int end = message.indexOf("\"}", start);
            if (end > start) {
                return message.substring(start, end);
            }
        }
        return message;
    }
}
