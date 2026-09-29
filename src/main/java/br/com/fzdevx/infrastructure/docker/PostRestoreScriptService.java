package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.domain.shared.PsqlErrorCollector;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Startup
@ApplicationScoped
public class PostRestoreScriptService {

    private static final String EPHEMERAL_LABEL = "docker-web-handler.ephemeral";
    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(\\d+)");

    @Inject
    Config config;

    @Inject
    DockerClient dockerClient;

    @Inject
    @ConfigProperty(name = "post-restore-scripts.enabled", defaultValue = "false")
    boolean featureEnabled;

    @Inject
    @ConfigProperty(name = "post-restore-scripts.on-failure", defaultValue = "stop")
    String onFailure;

    /**
     * Refuses to start on an unknown {@code on-failure} value. Anything but the two
     * known words used to fall through every "stop" check and let a failed script
     * pass as success, so a typo here is a deployment error, not a preference.
     */
    @PostConstruct
    void validateConfiguration() {
        onFailure = normalizeOnFailure(onFailure);
    }

    static String normalizeOnFailure(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.equals("stop") && !normalized.equals("continue")) {
            throw new IllegalStateException("post-restore-scripts.on-failure must be 'stop' or 'continue', got '"
                    + value + "'. Refusing to start: with an unknown value a failed script could pass as success.");
        }
        return normalized;
    }

    public boolean isEnabled() {
        return featureEnabled;
    }

    /** Normalized: always {@code stop} or {@code continue}. Applies to optional scripts only. */
    public String getOnFailure() {
        return onFailure;
    }

    /**
     * Decides whether a failed script ends the run. A mandatory script always
     * does - it exists to strip production configuration out of the restored
     * data, and "always run" means "must succeed". An optional one follows
     * {@code on-failure}. Emits the event that explains the decision.
     */
    boolean stopAfterFailure(PostRestoreScriptInfo script, Consumer<ContainerEvent> eventSink) {
        if (script.isMandatory()) {
            eventSink.accept(ContainerEvent.error("Running Scripts",
                    "Mandatory script '" + script.getFilename() + "' failed: the restore cannot continue."));
            return true;
        }
        if ("continue".equals(onFailure)) {
            eventSink.accept(ContainerEvent.info("Running Scripts",
                    "Optional script '" + script.getFilename() + "' failed; continuing (on-failure=continue)."));
            return false;
        }
        return true;
    }

    public List<PostRestoreScriptInfo> discoverMandatoryScripts(String repository) {
        return discoverScripts(getMandatoryDir(repository), true);
    }

    public List<PostRestoreScriptInfo> discoverOptionalScripts(String repository) {
        return discoverScripts(getOptionalDir(repository), false);
    }

    public List<PostRestoreScriptInfo> resolveScriptsToExecute(String repository,
                                                                List<String> selectedOptionalFilenames) {
        List<PostRestoreScriptInfo> result = new ArrayList<>(discoverMandatoryScripts(repository));

        if (selectedOptionalFilenames != null && !selectedOptionalFilenames.isEmpty()) {
            List<PostRestoreScriptInfo> allOptional = discoverOptionalScripts(repository);
            for (String filename : selectedOptionalFilenames) {
                Optional<String> validationError = InputValidator.validateScriptFilename(filename);
                if (validationError.isPresent()) {
                    Log.warnf("Rejected script filename '%s': %s", filename, validationError.get());
                    continue;
                }
                allOptional.stream()
                        .filter(s -> s.getFilename().equals(filename))
                        .findFirst()
                        .ifPresent(result::add);
            }
        }

        result.sort(Comparator.comparingInt(PostRestoreScriptInfo::getSortOrder)
                .thenComparing(PostRestoreScriptInfo::getFilename));
        return result;
    }

    /** Runs the scripts in order; returns whether the restore may proceed afterwards. */
    public boolean executeScripts(List<PostRestoreScriptInfo> scripts,
                                   DatabasePort.PgConnectionInfo pgInfo,
                                   String targetDb,
                                   String pgImage,
                                   String repository,
                                   Consumer<ContainerEvent> eventSink,
                                   AtomicBoolean cancelled) {
        if (scripts.isEmpty()) {
            eventSink.accept(ContainerEvent.info("Running Scripts", "No post-restore scripts to execute."));
            return true;
        }

        eventSink.accept(ContainerEvent.info("Running Scripts",
                "Executing " + scripts.size() + " post-restore script(s)..."));

        boolean useDocker = !"none".equalsIgnoreCase(pgImage);

        if (useDocker) {
            return executeViaDocker(scripts, pgInfo, targetDb, pgImage, repository, eventSink, cancelled);
        } else {
            return executeLocally(scripts, pgInfo, targetDb, repository, eventSink, cancelled);
        }
    }

    private boolean executeViaDocker(List<PostRestoreScriptInfo> scripts,
                                      DatabasePort.PgConnectionInfo pgInfo,
                                      String targetDb,
                                      String pgImage,
                                      String repository,
                                      Consumer<ContainerEvent> eventSink,
                                      AtomicBoolean cancelled) {
        // Collect script directories to copy into the ephemeral container
        Path mandatoryDir = resolveDirPath(getMandatoryDir(repository));
        Path optionalDir = resolveDirPath(getOptionalDir(repository));

        String containerId = null;
        try {
            eventSink.accept(ContainerEvent.info("Running Scripts", "Creating ephemeral script runner container..."));

            CreateContainerResponse container = dockerClient.createContainerCmd(pgImage)
                    .withCmd("tail", "-f", "/dev/null")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode("host"))
                    .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            // Create target directories before copying files
            ExecCreateCmdResponse mkdirExec = dockerClient.execCreateCmd(containerId)
                    .withCmd("mkdir", "-p", "/scripts/mandatory", "/scripts/optional")
                    .exec();
            dockerClient.execStartCmd(mkdirExec.getId()).exec(new ExecStartResultCallback())
                    .awaitCompletion();

            if (mandatoryDir != null && Files.isDirectory(mandatoryDir)) {
                dockerClient.copyArchiveToContainerCmd(containerId)
                        .withHostResource(mandatoryDir.toAbsolutePath().toString())
                        .withRemotePath("/scripts/mandatory/")
                        .withDirChildrenOnly(true)
                        .exec();
            }
            if (optionalDir != null && Files.isDirectory(optionalDir)) {
                dockerClient.copyArchiveToContainerCmd(containerId)
                        .withHostResource(optionalDir.toAbsolutePath().toString())
                        .withRemotePath("/scripts/optional/")
                        .withDirChildrenOnly(true)
                        .exec();
            }

            for (PostRestoreScriptInfo script : scripts) {
                if (cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Running Scripts", "Script execution cancelled."));
                    return false;
                }

                String scriptPath = resolveContainerScriptPath(script, repository);
                eventSink.accept(ContainerEvent.info("Running Scripts",
                        "Running: " + script.getFilename() + "..."));

                ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                        .withCmd(psqlCommand(pgInfo, targetDb, scriptPath).toArray(String[]::new))
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withEnv(List.of("PGPASSWORD=" + pgInfo.password()))
                        .exec();

                PipedInputStream stdoutPipe = new PipedInputStream();
                PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

                ExecStartResultCallback callback = dockerClient.execStartCmd(exec.getId())
                        .exec(new ExecStartResultCallback(stdoutSink, stdoutSink));

                PsqlErrorCollector errors = new PsqlErrorCollector();
                Thread outputReader = Thread.ofVirtual().start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutPipe))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            errors.accept(line);
                            eventSink.accept(ContainerEvent.progress("Running Scripts", line, -1));
                        }
                    } catch (Exception e) {
                        Log.warnf("Error reading script output: %s", e.getMessage());
                    }
                });

                callback.awaitCompletion();
                stdoutSink.close();
                outputReader.join();

                InspectExecResponse inspectResponse = dockerClient.inspectExecCmd(exec.getId()).exec();
                Long exitCodeLong = inspectResponse.getExitCodeLong();
                int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;

                if (!reportScriptOutcome(script, targetDb, exitCode, errors, eventSink)
                        && stopAfterFailure(script, eventSink)) {
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            Log.errorf("Script execution failed: %s", e.getMessage());
            eventSink.accept(ContainerEvent.error("Running Scripts",
                    "Script execution failed: " + e.getMessage()));
            return false;
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.stopContainerCmd(containerId).withTimeout(2).exec();
                } catch (Exception ignored) {}
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    eventSink.accept(ContainerEvent.info("Running Scripts",
                            "Ephemeral script runner container removed."));
                } catch (Exception e) {
                    Log.warnf("Failed to remove ephemeral script container: %s", e.getMessage());
                }
            }
        }
    }

    private boolean executeLocally(List<PostRestoreScriptInfo> scripts,
                                    DatabasePort.PgConnectionInfo pgInfo,
                                    String targetDb,
                                    String repository,
                                    Consumer<ContainerEvent> eventSink,
                                    AtomicBoolean cancelled) {
        for (PostRestoreScriptInfo script : scripts) {
            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Running Scripts", "Script execution cancelled."));
                return false;
            }

            Path scriptPath = resolveLocalScriptPath(script, repository);
            if (scriptPath == null || !Files.exists(scriptPath)) {
                eventSink.accept(ContainerEvent.error("Running Scripts",
                        "Script file not found: " + script.getFilename()));
                if (stopAfterFailure(script, eventSink)) return false;
                continue;
            }

            eventSink.accept(ContainerEvent.info("Running Scripts",
                    "Running: " + script.getFilename() + "..."));

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        psqlCommand(pgInfo, targetDb, scriptPath.toAbsolutePath().toString()));
                pb.environment().put("PGPASSWORD", pgInfo.password());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                PsqlErrorCollector errors = new PsqlErrorCollector();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errors.accept(line);
                        eventSink.accept(ContainerEvent.progress("Running Scripts", line, -1));
                    }
                }

                int exitCode = process.waitFor();
                if (!reportScriptOutcome(script, targetDb, exitCode, errors, eventSink)
                        && stopAfterFailure(script, eventSink)) {
                    return false;
                }
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Running Scripts",
                        "Script '" + script.getFilename() + "' execution error: " + e.getMessage()));
                if (stopAfterFailure(script, eventSink)) return false;
            }
        }

        return true;
    }

    /**
     * The psql invocation shared by both runners. {@code ON_ERROR_STOP} makes the
     * first SQL error end the script with exit code 3; without it psql keeps
     * going and exits 0, which is how a failed cleanup once passed as success.
     */
    static List<String> psqlCommand(DatabasePort.PgConnectionInfo pgInfo, String targetDb, String scriptPath) {
        return List.of("psql",
                "-v", "ON_ERROR_STOP=1",
                "-h", pgInfo.host(),
                "-p", String.valueOf(pgInfo.port()),
                "-U", pgInfo.user(),
                "-d", targetDb,
                "-f", scriptPath);
    }

    /**
     * Reports one script's result with the PostgreSQL errors it printed, so the
     * event (and through it the audit reason) says what failed, not only the
     * exit code. Returns whether the script is considered successful.
     *
     * <p>A script fails when psql exits non-zero <em>or</em> when PostgreSQL
     * printed an error, whichever comes first. psql runs with ON_ERROR_STOP so
     * the first SQL error already ends it with exit code 3; the error-line check
     * is the safety net for anything that still slips through with exit 0, and
     * an error swallowed there is exactly how an unsanitized production copy
     * once went live as "homologation".</p>
     */
    boolean reportScriptOutcome(PostRestoreScriptInfo script, String targetDb, int exitCode,
                                PsqlErrorCollector errors, Consumer<ContainerEvent> eventSink) {
        String name = script.getFilename();
        if (exitCode == 0 && errors.isEmpty()) {
            eventSink.accept(ContainerEvent.info("Running Scripts",
                    "Script '" + name + "' completed successfully."));
            return true;
        }
        String headline = exitCode != 0
                ? "Script '" + name + "' failed with exit code " + exitCode
                : "Script '" + name + "' exited with code 0 but PostgreSQL reported errors, so it is treated as failed";
        String detail = errors.isEmpty()
                ? " (no PostgreSQL error line was printed; see the output above)."
                : ". PostgreSQL reported " + errors.summary();
        Log.errorf("Post-restore script '%s' failed on database '%s' (exit code %d)%s",
                name, targetDb, exitCode, detail);
        eventSink.accept(ContainerEvent.error("Running Scripts", headline + detail));
        return false;
    }

    private List<PostRestoreScriptInfo> discoverScripts(Optional<String> dirOpt, boolean mandatory) {
        if (dirOpt.isEmpty()) return List.of();

        Path dir = Path.of(dirOpt.get());
        if (!Files.isDirectory(dir)) return List.of();

        List<PostRestoreScriptInfo> scripts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String filename = file.getFileName().toString();
                int sortOrder = extractSortOrder(filename);
                long fileSize = Files.size(file);
                scripts.add(new PostRestoreScriptInfo(filename, sortOrder, fileSize, mandatory));
            }
        } catch (Exception e) {
            Log.warnf("Failed to list scripts in %s: %s", dirOpt.get(), e.getMessage());
        }

        scripts.sort(Comparator.comparingInt(PostRestoreScriptInfo::getSortOrder)
                .thenComparing(PostRestoreScriptInfo::getFilename));
        return scripts;
    }

    private int extractSortOrder(String filename) {
        Matcher m = NUMERIC_PREFIX.matcher(filename);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return Integer.MAX_VALUE;
    }

    private Optional<String> getMandatoryDir(String repository) {
        return config.getOptionalValue("repository.post-restore-mandatory-dir." + repository, String.class)
                .filter(v -> !v.isBlank());
    }

    private Optional<String> getOptionalDir(String repository) {
        return config.getOptionalValue("repository.post-restore-optional-dir." + repository, String.class)
                .filter(v -> !v.isBlank());
    }

    private Path resolveDirPath(Optional<String> dirOpt) {
        if (dirOpt.isEmpty()) return null;
        Path p = Path.of(dirOpt.get());
        return Files.isDirectory(p) ? p : null;
    }

    private String resolveContainerScriptPath(PostRestoreScriptInfo script, String repository) {
        // Check if the script belongs to mandatory or optional dir
        Optional<String> mandatoryDir = getMandatoryDir(repository);
        if (mandatoryDir.isPresent()) {
            Path localPath = Path.of(mandatoryDir.get(), script.getFilename());
            if (Files.exists(localPath)) {
                return "/scripts/mandatory/" + script.getFilename();
            }
        }
        return "/scripts/optional/" + script.getFilename();
    }

    private Path resolveLocalScriptPath(PostRestoreScriptInfo script, String repository) {
        Optional<String> mandatoryDir = getMandatoryDir(repository);
        if (mandatoryDir.isPresent()) {
            Path p = Path.of(mandatoryDir.get(), script.getFilename());
            if (Files.exists(p)) return p;
        }
        Optional<String> optionalDir = getOptionalDir(repository);
        if (optionalDir.isPresent()) {
            Path p = Path.of(optionalDir.get(), script.getFilename());
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
