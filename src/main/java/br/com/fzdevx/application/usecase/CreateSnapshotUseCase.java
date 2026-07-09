package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.application.dto.CreateSnapshotRequest;
import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.domain.shared.DateTimeParser;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class CreateSnapshotUseCase {

    @Inject
    br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    private static final String EPHEMERAL_LABEL = "docker-web-handler.ephemeral";
    private static final int TEMPORARY_SNAPSHOT_TTL_SECONDS = 120;

    @Inject
    DatabaseService databaseService;

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    DockerClient dockerClient;

    @Inject
    ResourceCounterService resourceCounterService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    @Inject
    ManagedDatabaseUsageTracker usageTracker;

    public record ActiveSnapshotInfo(String repository, String sourceDatabaseName) {}

    static class SnapshotContext {
        final ActiveSnapshotInfo info;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String ephemeralContainerId;
        volatile Process localProcess;

        SnapshotContext(ActiveSnapshotInfo info) {
            this.info = info;
        }
    }

    private final ConcurrentHashMap<String, SnapshotContext> activeSnapshots = new ConcurrentHashMap<>();

    public List<ActiveSnapshotInfo> getActiveSnapshots() {
        return activeSnapshots.values().stream().map(ctx -> ctx.info).toList();
    }

    public boolean cancel(String repository, String sourceDatabaseName) {
        String lockKey = repository + ":" + sourceDatabaseName;
        SnapshotContext ctx = activeSnapshots.get(lockKey);
        if (ctx == null) return false;

        ctx.cancelled.set(true);

        if (ctx.ephemeralContainerId != null) {
            try {
                dockerClient.stopContainerCmd(ctx.ephemeralContainerId).withTimeout(2).exec();
            } catch (Exception ignored) {}
        }

        if (ctx.localProcess != null) {
            ctx.localProcess.destroyForcibly();
        }

        return true;
    }

    public String executeSave(CreateSnapshotRequest request, Consumer<ContainerEvent> eventSink) {
        eventSink.accept(ContainerEvent.info("Validating", "Validating snapshot request..."));

        Optional<String> error = validateRequest(request);
        if (error.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", error.get()));
            return null;
        }

        DatabaseSnapshot.Format format = DatabaseSnapshot.Format.valueOf(request.getFormat());

        String lockKey = request.getRepository() + ":" + request.getSourceDatabaseName();
        SnapshotContext ctx = new SnapshotContext(
                new ActiveSnapshotInfo(request.getRepository(), request.getSourceDatabaseName()));
        if (activeSnapshots.putIfAbsent(lockKey, ctx) != null) {
            eventSink.accept(ContainerEvent.error("Validating",
                    "A snapshot is already in progress for " + request.getSourceDatabaseName()
                            + " on repository " + request.getRepository() + "."));
            return null;
        }

        DatabaseSnapshot snapshot = new DatabaseSnapshot(
                request.getRepository(), request.getSourceDatabaseName(), format, request.getLabel());
        snapshot.setCreatedBy(actorResolver.usernameOrSystem());
        // resolved and validated at prepare time - here we may be off the request thread
        snapshot.setTenantId(request.getTenantId());
        snapshot.setSharedWithTenants(request.getSharedWithTenants());
        snapshot.setContainerName(request.getContainerName());
        snapshot.setDescription(request.getDescription());
        snapshot.setTemporary(request.isTemporary());
        if (request.isTemporary()) {
            snapshot.setExpiresAt(Instant.now().plusSeconds(TEMPORARY_SNAPSHOT_TTL_SECONDS));
        } else {
            Instant expiresAt = DateTimeParser.parseExpiresAt(request.getExpiresAt());
            snapshot.setExpiresAt(expiresAt);
        }

        try {
            eventSink.accept(ContainerEvent.info("Validating", "Validation passed."));

            String pgImage = databaseService.getContainerImage(request.getRepository());
            DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(request.getRepository());

            int exitCode;
            if (!"none".equalsIgnoreCase(pgImage)) {
                exitCode = executeDockerSnapshot(snapshot, pgInfo, pgImage, eventSink, ctx);
            } else {
                exitCode = executeLocalSnapshot(snapshot, pgInfo, eventSink, ctx);
            }

            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating Snapshot", "Snapshot cancelled by user."));
                snapshotStorageService.cleanupFile(snapshot);
                return null;
            }

            if (exitCode != 0) {
                eventSink.accept(ContainerEvent.error("Creating Snapshot",
                        "pg_dump exited with code " + exitCode + ". Check logs above."));
                snapshotStorageService.cleanupFile(snapshot);
                return null;
            }

            eventSink.accept(ContainerEvent.info("Saving", "Snapshot saved successfully."));
            snapshotStorageService.saveMetadata(snapshot);
            resourceCounterService.increment(ResourceCounterService.SNAPSHOTS);

            usageTracker.markUsed(request.getRepository(), request.getSourceDatabaseName());

            return snapshot.getId();

        } catch (Exception e) {
            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating Snapshot", "Snapshot cancelled by user."));
            } else {
                Log.errorf("Snapshot failed: %s", e.getMessage());
                eventSink.accept(ContainerEvent.error("Creating Snapshot",
                        "Snapshot failed: " + e.getMessage()));
            }
            snapshotStorageService.cleanupFile(snapshot);
            return null;
        } finally {
            activeSnapshots.remove(lockKey);
        }
    }

    private int executeDockerSnapshot(DatabaseSnapshot snapshot,
                                       DatabasePort.PgConnectionInfo pgInfo,
                                       String pgImage,
                                       Consumer<ContainerEvent> eventSink,
                                       SnapshotContext ctx) throws Exception {
        eventSink.accept(ContainerEvent.info("Pulling Image", "Pulling image '" + pgImage + "'..."));
        dockerClient.pullImageCmd(pgImage)
                .exec(new PullImageResultCallback() {
                    @Override
                    public void onNext(PullResponseItem item) {
                        super.onNext(item);
                        if (item.getStatus() != null) {
                            String msg = item.getId() != null
                                    ? item.getId() + ": " + item.getStatus()
                                    : item.getStatus();
                            eventSink.accept(ContainerEvent.progress("Pulling Image", msg, -1));
                        }
                    }
                })
                .awaitCompletion();

        if (ctx.cancelled.get()) return -1;

        eventSink.accept(ContainerEvent.info("Creating Snapshot",
                "Creating ephemeral snapshot container..."));
        CreateContainerResponse container = dockerClient.createContainerCmd(pgImage)
                .withCmd("tail", "-f", "/dev/null")
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("host"))
                .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                .exec();

        String containerId = container.getId();
        ctx.ephemeralContainerId = containerId;
        try {
            dockerClient.startContainerCmd(containerId).exec();

            if (ctx.cancelled.get()) return -1;

            List<String> cmd = buildPgDumpCommand(pgInfo, snapshot.getSourceDatabaseName(), snapshot.getFormat());

            eventSink.accept(ContainerEvent.info("Creating Snapshot",
                    "Running pg_dump on database '" + snapshot.getSourceDatabaseName() + "'..."));

            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd.toArray(String[]::new))
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withEnv(List.of("PGPASSWORD=" + pgInfo.password()))
                    .exec();

            PipedInputStream stdoutPipe = new PipedInputStream(65536);
            PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

            PipedInputStream stderrPipe = new PipedInputStream();
            PipedOutputStream stderrSink = new PipedOutputStream(stderrPipe);

            ExecStartResultCallback callback = dockerClient.execStartCmd(exec.getId())
                    .exec(new ExecStartResultCallback(stdoutSink, stderrSink));

            Thread stderrReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderrPipe))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        eventSink.accept(ContainerEvent.progress("Creating Snapshot", line, -1));
                    }
                } catch (Exception e) {
                    Log.warnf("Error reading pg_dump stderr: %s", e.getMessage());
                }
            });

            final SnapshotStorageService.StoreResult[] resultHolder = new SnapshotStorageService.StoreResult[1];
            Thread storeThread = Thread.ofVirtual().start(() -> {
                try {
                    resultHolder[0] = snapshotStorageService.storeFromStream(stdoutPipe, snapshot);
                } catch (Exception e) {
                    Log.errorf("Error storing snapshot stream: %s", e.getMessage());
                }
            });

            callback.awaitCompletion();
            stdoutSink.close();
            stderrSink.close();
            stderrReader.join();
            storeThread.join();

            if (resultHolder[0] != null) {
                snapshot.setFileSize(resultHolder[0].bytesWritten());
                snapshot.setMd5Hash(resultHolder[0].md5Hash());
            }

            if (ctx.cancelled.get()) return -1;

            InspectExecResponse inspectResponse = dockerClient.inspectExecCmd(exec.getId()).exec();
            Long exitCodeLong = inspectResponse.getExitCodeLong();
            return exitCodeLong != null ? exitCodeLong.intValue() : -1;

        } finally {
            ctx.ephemeralContainerId = null;
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(2).exec();
            } catch (Exception ignored) {}
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                eventSink.accept(ContainerEvent.info("Creating Snapshot",
                        "Ephemeral snapshot container removed."));
            } catch (Exception e) {
                Log.warnf("Failed to remove ephemeral container %s: %s", containerId, e.getMessage());
            }
        }
    }

    private int executeLocalSnapshot(DatabaseSnapshot snapshot,
                                      DatabasePort.PgConnectionInfo pgInfo,
                                      Consumer<ContainerEvent> eventSink,
                                      SnapshotContext ctx) throws Exception {
        List<String> cmd = buildPgDumpCommand(pgInfo, snapshot.getSourceDatabaseName(), snapshot.getFormat());

        eventSink.accept(ContainerEvent.info("Creating Snapshot",
                "Running pg_dump on database '" + snapshot.getSourceDatabaseName() + "'..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", pgInfo.password());

        Process process = pb.start();
        ctx.localProcess = process;
        try {
            Thread stderrReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        eventSink.accept(ContainerEvent.progress("Creating Snapshot", line, -1));
                    }
                } catch (Exception e) {
                    Log.warnf("Error reading pg_dump stderr: %s", e.getMessage());
                }
            });

            SnapshotStorageService.StoreResult result =
                    snapshotStorageService.storeFromStream(process.getInputStream(), snapshot);

            stderrReader.join();

            snapshot.setFileSize(result.bytesWritten());
            snapshot.setMd5Hash(result.md5Hash());

            return process.waitFor();
        } finally {
            ctx.localProcess = null;
        }
    }

    private List<String> buildPgDumpCommand(DatabasePort.PgConnectionInfo pgInfo,
                                             String databaseName,
                                             DatabaseSnapshot.Format format) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("pg_dump",
                "-h", pgInfo.host(),
                "-p", String.valueOf(pgInfo.port()),
                "-U", pgInfo.user(),
                "-d", databaseName));

        if (format == DatabaseSnapshot.Format.CUSTOM) {
            cmd.add("-Fc");
        } else {
            cmd.add("-Fp");
        }
        return cmd;
    }

    private Optional<String> validateRequest(CreateSnapshotRequest request) {
        Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
        if (repoError.isPresent()) return repoError;

        Optional<String> dbError = InputValidator.validateDatabaseName(request.getSourceDatabaseName());
        if (dbError.isPresent()) return dbError;

        Optional<String> formatError = InputValidator.validateSnapshotFormat(request.getFormat());
        if (formatError.isPresent()) return formatError;

        if (request.getLabel() != null && !request.getLabel().isBlank()) {
            Optional<String> labelError = InputValidator.validateSnapshotLabel(request.getLabel());
            if (labelError.isPresent()) return labelError;
        }

        if (!databaseService.hasDatabaseConfig(request.getRepository())) {
            return Optional.of("No database configuration found for repository: " + request.getRepository());
        }

        return Optional.empty();
    }

}
