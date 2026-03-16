package br.com.fzdevx.service;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.model.PostRestoreScriptInfo;
import br.com.fzdevx.util.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.quarkus.logging.Log;
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

    public boolean isEnabled() {
        return featureEnabled;
    }

    public String getOnFailure() {
        return onFailure;
    }

    public List<PostRestoreScriptInfo> discoverMandatoryScripts(String repository) {
        return discoverScripts(getMandatoryDir(repository));
    }

    public List<PostRestoreScriptInfo> discoverOptionalScripts(String repository) {
        return discoverScripts(getOptionalDir(repository));
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

    public boolean executeScripts(List<PostRestoreScriptInfo> scripts,
                                   DatabaseService.PgConnectionInfo pgInfo,
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
                                      DatabaseService.PgConnectionInfo pgInfo,
                                      String targetDb,
                                      String pgImage,
                                      String repository,
                                      Consumer<ContainerEvent> eventSink,
                                      AtomicBoolean cancelled) {
        // Collect all script directories to bind-mount
        Path mandatoryDir = resolveDirPath(getMandatoryDir(repository));
        Path optionalDir = resolveDirPath(getOptionalDir(repository));

        String containerId = null;
        try {
            eventSink.accept(ContainerEvent.info("Running Scripts", "Creating ephemeral script runner container..."));

            List<Bind> binds = new ArrayList<>();
            if (mandatoryDir != null && Files.isDirectory(mandatoryDir)) {
                binds.add(Bind.parse(mandatoryDir.toAbsolutePath() + ":/scripts/mandatory:ro"));
            }
            if (optionalDir != null && Files.isDirectory(optionalDir)) {
                binds.add(Bind.parse(optionalDir.toAbsolutePath() + ":/scripts/optional:ro"));
            }

            CreateContainerResponse container = dockerClient.createContainerCmd(pgImage)
                    .withCmd("tail", "-f", "/dev/null")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode("host")
                            .withBinds(binds))
                    .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            boolean allSuccess = true;
            for (PostRestoreScriptInfo script : scripts) {
                if (cancelled.get()) {
                    eventSink.accept(ContainerEvent.error("Running Scripts", "Script execution cancelled."));
                    return false;
                }

                String scriptPath = resolveContainerScriptPath(script, repository);
                eventSink.accept(ContainerEvent.info("Running Scripts",
                        "Running: " + script.getFilename() + "..."));

                ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                        .withCmd("psql",
                                "-h", pgInfo.host(),
                                "-p", String.valueOf(pgInfo.port()),
                                "-U", pgInfo.user(),
                                "-d", targetDb,
                                "-f", scriptPath)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withEnv(List.of("PGPASSWORD=" + pgInfo.password()))
                        .exec();

                PipedInputStream stdoutPipe = new PipedInputStream();
                PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

                ExecStartResultCallback callback = dockerClient.execStartCmd(exec.getId())
                        .exec(new ExecStartResultCallback(stdoutSink, stdoutSink));

                Thread outputReader = Thread.ofVirtual().start(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutPipe))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
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

                if (exitCode != 0) {
                    eventSink.accept(ContainerEvent.error("Running Scripts",
                            "Script '" + script.getFilename() + "' failed with exit code " + exitCode + "."));
                    if ("stop".equalsIgnoreCase(onFailure)) {
                        return false;
                    }
                    allSuccess = false;
                } else {
                    eventSink.accept(ContainerEvent.info("Running Scripts",
                            "Script '" + script.getFilename() + "' completed successfully."));
                }
            }

            return allSuccess || "continue".equalsIgnoreCase(onFailure);

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
                                    DatabaseService.PgConnectionInfo pgInfo,
                                    String targetDb,
                                    String repository,
                                    Consumer<ContainerEvent> eventSink,
                                    AtomicBoolean cancelled) {
        boolean allSuccess = true;

        for (PostRestoreScriptInfo script : scripts) {
            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Running Scripts", "Script execution cancelled."));
                return false;
            }

            Path scriptPath = resolveLocalScriptPath(script, repository);
            if (scriptPath == null || !Files.exists(scriptPath)) {
                eventSink.accept(ContainerEvent.error("Running Scripts",
                        "Script file not found: " + script.getFilename()));
                if ("stop".equalsIgnoreCase(onFailure)) return false;
                allSuccess = false;
                continue;
            }

            eventSink.accept(ContainerEvent.info("Running Scripts",
                    "Running: " + script.getFilename() + "..."));

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "psql",
                        "-h", pgInfo.host(),
                        "-p", String.valueOf(pgInfo.port()),
                        "-U", pgInfo.user(),
                        "-d", targetDb,
                        "-f", scriptPath.toAbsolutePath().toString());
                pb.environment().put("PGPASSWORD", pgInfo.password());
                pb.redirectErrorStream(true);

                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        eventSink.accept(ContainerEvent.progress("Running Scripts", line, -1));
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    eventSink.accept(ContainerEvent.error("Running Scripts",
                            "Script '" + script.getFilename() + "' failed with exit code " + exitCode + "."));
                    if ("stop".equalsIgnoreCase(onFailure)) return false;
                    allSuccess = false;
                } else {
                    eventSink.accept(ContainerEvent.info("Running Scripts",
                            "Script '" + script.getFilename() + "' completed successfully."));
                }
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Running Scripts",
                        "Script '" + script.getFilename() + "' execution error: " + e.getMessage()));
                if ("stop".equalsIgnoreCase(onFailure)) return false;
                allSuccess = false;
            }
        }

        return allSuccess || "continue".equalsIgnoreCase(onFailure);
    }

    private List<PostRestoreScriptInfo> discoverScripts(Optional<String> dirOpt) {
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
                scripts.add(new PostRestoreScriptInfo(filename, sortOrder, fileSize));
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
