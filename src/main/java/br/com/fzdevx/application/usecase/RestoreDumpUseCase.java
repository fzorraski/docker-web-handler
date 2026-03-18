package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.docker.PostRestoreScriptService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import br.com.fzdevx.domain.shared.InputValidator;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

@ApplicationScoped
public class RestoreDumpUseCase {

    @Inject
    DumpStorageService dumpStorageService;

    @Inject
    DatabaseService databaseService;

    @Inject
    PostRestoreScriptService postRestoreScriptService;

    @Inject
    SnapshotStorageService snapshotStorageService;

    @Inject
    DockerClient dockerClient;

    public static final String EPHEMERAL_LABEL = "docker-web-handler.ephemeral";

    private static final Pattern WARNINGS_IGNORED_PATTERN =
            Pattern.compile("errors ignored on restore:\\s*(\\d+)");

    private record RestoreResult(int exitCode, int warningsIgnored) {}

    public record ActiveRestoreInfo(String repository, String targetDatabase, String dumpFilename) {}

    static class RestoreContext {
        final ActiveRestoreInfo info;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String ephemeralContainerId;
        volatile Process localProcess;

        RestoreContext(ActiveRestoreInfo info) {
            this.info = info;
        }
    }

    private final ConcurrentHashMap<String, RestoreContext> activeRestores = new ConcurrentHashMap<>();
    private final AtomicInteger totalRestores = new AtomicInteger(0);

    public List<ActiveRestoreInfo> getActiveRestores() {
        return activeRestores.values().stream().map(ctx -> ctx.info).toList();
    }

    public int getTotalRestores() {
        return totalRestores.get();
    }

    public boolean cancel(String repository, String targetDatabase) {
        String lockKey = repository + ":" + targetDatabase;
        RestoreContext ctx = activeRestores.get(lockKey);
        if (ctx == null) return false;

        ctx.cancelled.set(true);

        if (ctx.ephemeralContainerId != null) {
            try {
                dockerClient.stopContainerCmd(ctx.ephemeralContainerId).withTimeout(2).exec();
            } catch (Exception ignored) {
            }
        }

        if (ctx.localProcess != null) {
            ctx.localProcess.destroyForcibly();
        }

        return true;
    }

    public boolean execute(RestoreDumpRequest request, Consumer<ContainerEvent> eventSink) {
        boolean isSnapshot = request.getSnapshotId() != null && !request.getSnapshotId().isBlank();
        String sourceId = isSnapshot ? request.getSnapshotId() : request.getDumpId();

        // Step 1: Validate
        eventSink.accept(ContainerEvent.info("Validating", "Validating restore request..."));

        Optional<String> uuidError = InputValidator.validateUuid(sourceId);
        if (uuidError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", uuidError.get()));
            return false;
        }

        Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
        if (repoError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", repoError.get()));
            return false;
        }

        Optional<String> dbError = InputValidator.validateDatabaseName(request.getTargetDatabase());
        if (dbError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", dbError.get()));
            return false;
        }

        if (!databaseService.hasDatabaseConfig(request.getRepository())) {
            eventSink.accept(ContainerEvent.error("Validating",
                    "No database configuration found for repository: " + request.getRepository()));
            return false;
        }

        // Resolve source: dump or snapshot
        DatabaseDump dump = null;
        DatabaseSnapshot snapshot = null;
        String displayName;
        if (isSnapshot) {
            Optional<DatabaseSnapshot> snapOpt = snapshotStorageService.findById(request.getSnapshotId());
            if (snapOpt.isEmpty()) {
                eventSink.accept(ContainerEvent.error("Validating", "Snapshot not found: " + request.getSnapshotId()));
                return false;
            }
            snapshot = snapOpt.get();
            displayName = snapshot.getLabel() != null && !snapshot.getLabel().isBlank()
                    ? snapshot.getLabel() : "snapshot-" + snapshot.getSourceDatabaseName();
            // Create a synthetic dump for the restore methods (reuse format mapping)
            dump = new DatabaseDump();
            dump.setFormat(snapshot.getFormat() == DatabaseSnapshot.Format.SQL
                    ? DatabaseDump.Format.SQL : DatabaseDump.Format.CUSTOM);
            dump.setOriginalFilename(displayName);
        } else {
            Optional<DatabaseDump> dumpOpt = dumpStorageService.findById(request.getDumpId());
            if (dumpOpt.isEmpty()) {
                eventSink.accept(ContainerEvent.error("Validating", "Dump not found: " + request.getDumpId()));
                return false;
            }
            dump = dumpOpt.get();
            displayName = dump.getOriginalFilename();
        }

        final DatabaseDump dumpFinal = dump;
        final DatabaseSnapshot snapshotFinal = snapshot;

        // Concurrency check
        String lockKey = request.getRepository() + ":" + request.getTargetDatabase();
        RestoreContext ctx = new RestoreContext(new ActiveRestoreInfo(
                request.getRepository(), request.getTargetDatabase(), displayName));
        if (activeRestores.putIfAbsent(lockKey, ctx) != null) {
            eventSink.accept(ContainerEvent.error("Validating",
                    "A restore is already in progress for " + request.getTargetDatabase()
                            + " on repository " + request.getRepository() + ". Please wait."));
            return false;
        }

        Path tempFile = null;
        boolean databaseCreated = false;
        try {
            eventSink.accept(ContainerEvent.info("Validating", "Validation passed."));

            // Step 2: Prepare
            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Preparing", "Restore cancelled by user."));
                return false;
            }
            eventSink.accept(ContainerEvent.info("Preparing", "Decompressing file..."));
            if (isSnapshot) {
                tempFile = snapshotStorageService.prepareForRestore(snapshotFinal);
            } else {
                tempFile = dumpStorageService.prepareForRestore(dump);
            }
            eventSink.accept(ContainerEvent.info("Preparing", "File ready."));

            // Step 3: Create database if needed
            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Creating Database", "Restore cancelled by user."));
                return false;
            }
            if (request.isCreateDatabase()) {
                eventSink.accept(ContainerEvent.info("Creating Database",
                        "Creating database '" + request.getTargetDatabase() + "'..."));
                if (databaseService.databaseExists(request.getRepository(), request.getTargetDatabase())) {
                    eventSink.accept(ContainerEvent.info("Creating Database",
                            "Database already exists, skipping creation."));
                } else {
                    databaseService.createDatabase(request.getRepository(), request.getTargetDatabase());
                    databaseCreated = true;
                    eventSink.accept(ContainerEvent.info("Creating Database", "Database created."));
                }
            } else {
                eventSink.accept(ContainerEvent.info("Creating Database", "Skipping database creation."));
            }

            // Step 4: Restore
            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Restoring", "Restore cancelled by user."));
                return false;
            }
            eventSink.accept(ContainerEvent.info("Restoring",
                    "Restoring dump '" + dump.getOriginalFilename() + "' into '" + request.getTargetDatabase() + "'..."));

            String pgImage = databaseService.getContainerImage(request.getRepository());
            DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(request.getRepository());

            RestoreResult result;
            if (!"none".equalsIgnoreCase(pgImage)) {
                result = executeDockerRestore(dump, tempFile, pgInfo, request.getTargetDatabase(),
                        pgImage, eventSink, ctx);
            } else {
                result = executeLocalRestore(dump, tempFile, pgInfo, request.getTargetDatabase(), eventSink, ctx);
            }

            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Restoring", "Restore cancelled by user."));
                return false;
            }

            if (result.exitCode() != 0) {
                boolean isNonFatalWarning = dump.getFormat() != DatabaseDump.Format.SQL
                        && result.exitCode() == 1 && result.warningsIgnored() > 0;
                if (isNonFatalWarning) {
                    eventSink.accept(ContainerEvent.info("Restoring",
                            "Restore completed with " + result.warningsIgnored()
                                    + " non-fatal warning(s) ignored."));
                } else {
                    eventSink.accept(ContainerEvent.error("Restoring",
                            "Restore process exited with code " + result.exitCode()
                                    + ". Check logs above for details."));
                    return false;
                }
            }

            eventSink.accept(ContainerEvent.info("Restoring",
                    "Dump '" + dump.getOriginalFilename() + "' restored into '"
                            + request.getTargetDatabase() + "'."));

            // Step 5: Run post-restore scripts if enabled
            if (postRestoreScriptService.isEnabled()) {
                List<PostRestoreScriptInfo> scripts = postRestoreScriptService.resolveScriptsToExecute(
                        request.getRepository(), request.getSelectedOptionalScripts());
                if (!scripts.isEmpty()) {
                    if (ctx.cancelled.get()) {
                        eventSink.accept(ContainerEvent.error("Running Scripts", "Restore cancelled by user."));
                        return false;
                    }
                    boolean scriptsOk = postRestoreScriptService.executeScripts(
                            scripts, pgInfo, request.getTargetDatabase(), pgImage,
                            request.getRepository(), eventSink, ctx.cancelled);
                    if (!scriptsOk && "stop".equalsIgnoreCase(postRestoreScriptService.getOnFailure())) {
                        return false;
                    }
                }
            }

            // Track usage on successful restore
            if (isSnapshot) {
                snapshotStorageService.markUsed(request.getSnapshotId());
            } else {
                dumpStorageService.markUsed(request.getDumpId());
            }
            totalRestores.incrementAndGet();

            return true;

        } catch (Exception e) {
            if (ctx.cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Restoring", "Restore cancelled by user."));
            } else {
                Log.errorf("Restore failed: %s", e.getMessage());
                eventSink.accept(ContainerEvent.error("Restoring", "Restore failed: " + e.getMessage()));
            }
            return false;
        } finally {
            if (ctx.cancelled.get() && databaseCreated) {
                try {
                    databaseService.dropDatabase(request.getRepository(), request.getTargetDatabase());
                    eventSink.accept(ContainerEvent.info("Restoring",
                            "Dropped newly created database '" + request.getTargetDatabase() + "' due to cancellation."));
                } catch (Exception e) {
                    Log.warnf("Failed to drop database '%s' after cancellation: %s",
                            request.getTargetDatabase(), e.getMessage());
                }
            }
            activeRestores.remove(lockKey);
            dumpStorageService.cleanupTempFile(tempFile);
        }
    }

    private RestoreResult executeDockerRestore(DatabaseDump dump, Path dumpFile,
                                     DatabasePort.PgConnectionInfo pgInfo,
                                     String targetDatabase, String image,
                                     Consumer<ContainerEvent> eventSink,
                                     RestoreContext ctx) throws Exception {
        // Pull image if needed
        eventSink.accept(ContainerEvent.info("Restoring", "Pulling image '" + image + "'..."));
        dockerClient.pullImageCmd(image)
                .exec(new PullImageResultCallback() {
                    @Override
                    public void onNext(PullResponseItem item) {
                        super.onNext(item);
                        if (item.getStatus() != null) {
                            String msg = item.getId() != null
                                    ? item.getId() + ": " + item.getStatus()
                                    : item.getStatus();
                            eventSink.accept(ContainerEvent.progress("Restoring", msg, -1));
                        }
                    }
                })
                .awaitCompletion();

        if (ctx.cancelled.get()) return new RestoreResult(-1, 0);

        // Create ephemeral container with host network (to reach PG server)
        eventSink.accept(ContainerEvent.info("Restoring", "Creating ephemeral restore container..."));
        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withCmd("tail", "-f", "/dev/null")
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("host"))
                .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                .exec();

        String containerId = container.getId();
        ctx.ephemeralContainerId = containerId;
        try {
            dockerClient.startContainerCmd(containerId).exec();

            if (ctx.cancelled.get()) return new RestoreResult(-1, 0);

            // Build restore command
            List<String> cmd = new ArrayList<>();
            if (dump.getFormat() == DatabaseDump.Format.SQL) {
                cmd.addAll(List.of("psql",
                        "-h", pgInfo.host(),
                        "-p", String.valueOf(pgInfo.port()),
                        "-U", pgInfo.user(),
                        "-d", targetDatabase));
            } else {
                cmd.addAll(List.of("pg_restore",
                        "-h", pgInfo.host(),
                        "-p", String.valueOf(pgInfo.port()),
                        "-U", pgInfo.user(),
                        "-d", targetDatabase,
                        "--no-owner", "--no-privileges"));
            }

            // Exec restore with stdin pipe
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd.toArray(String[]::new))
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withEnv(List.of("PGPASSWORD=" + pgInfo.password()))
                    .exec();

            PipedInputStream stdoutPipe = new PipedInputStream();
            PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

            InputStream dumpInput = Files.newInputStream(dumpFile);

            ExecStartResultCallback callback = dockerClient.execStartCmd(exec.getId())
                    .withStdIn(dumpInput)
                    .exec(new ExecStartResultCallback(stdoutSink, stdoutSink));

            AtomicInteger warningsIgnored = new AtomicInteger(0);

            Thread outputReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutPipe))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        eventSink.accept(ContainerEvent.progress("Restoring", line, -1));
                        Matcher m = WARNINGS_IGNORED_PATTERN.matcher(line);
                        if (m.find()) {
                            warningsIgnored.set(Integer.parseInt(m.group(1)));
                        }
                    }
                } catch (Exception e) {
                    Log.warnf("Error reading restore output: %s", e.getMessage());
                }
            });

            callback.awaitCompletion();
            dumpInput.close();
            stdoutSink.close();
            outputReader.join();

            if (ctx.cancelled.get()) return new RestoreResult(-1, 0);

            InspectExecResponse inspectResponse = dockerClient.inspectExecCmd(exec.getId()).exec();
            Long exitCodeLong = inspectResponse.getExitCodeLong();
            int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;
            return new RestoreResult(exitCode, warningsIgnored.get());

        } finally {
            ctx.ephemeralContainerId = null;
            // Always remove the ephemeral container
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(2).exec();
            } catch (Exception ignored) {
            }
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                eventSink.accept(ContainerEvent.info("Restoring", "Ephemeral restore container removed."));
            } catch (Exception e) {
                Log.warnf("Failed to remove ephemeral container %s: %s", containerId, e.getMessage());
            }
        }
    }

    private RestoreResult executeLocalRestore(DatabaseDump dump, Path dumpFile,
                                    DatabasePort.PgConnectionInfo pgInfo,
                                    String targetDatabase,
                                    Consumer<ContainerEvent> eventSink,
                                    RestoreContext ctx) throws Exception {
        ProcessBuilder pb;
        if (dump.getFormat() == DatabaseDump.Format.SQL) {
            pb = new ProcessBuilder(
                    "psql",
                    "-h", pgInfo.host(),
                    "-p", String.valueOf(pgInfo.port()),
                    "-U", pgInfo.user(),
                    "-d", targetDatabase,
                    "-f", dumpFile.toAbsolutePath().toString());
        } else {
            pb = new ProcessBuilder(
                    "pg_restore",
                    "-h", pgInfo.host(),
                    "-p", String.valueOf(pgInfo.port()),
                    "-U", pgInfo.user(),
                    "-d", targetDatabase,
                    "--no-owner",
                    "--no-privileges",
                    dumpFile.toAbsolutePath().toString());
        }

        pb.environment().put("PGPASSWORD", pgInfo.password());
        pb.redirectErrorStream(true);

        int warningsIgnored = 0;
        Process process = pb.start();
        ctx.localProcess = process;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                eventSink.accept(ContainerEvent.progress("Restoring", line, -1));
                Matcher m = WARNINGS_IGNORED_PATTERN.matcher(line);
                if (m.find()) {
                    warningsIgnored = Integer.parseInt(m.group(1));
                }
            }
        } finally {
            ctx.localProcess = null;
        }

        return new RestoreResult(process.waitFor(), warningsIgnored);
    }
}
