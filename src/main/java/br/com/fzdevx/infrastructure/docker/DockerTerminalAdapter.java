package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerTerminalPort;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.async.ResultCallback;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class DockerTerminalAdapter implements DockerTerminalPort {

    @Inject
    DockerClient dockerClient;

    @Inject
    @ConfigProperty(name = "container.terminal.default-shell", defaultValue = "/bin/bash")
    String defaultShell;

    @Override
    public String createExecSession(String containerId, String preferredShell) throws Exception {
        String shell = preferredShell != null ? preferredShell : defaultShell;
        try {
            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                    .withCmd(shell)
                    .withTty(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            Log.infof("Created terminal exec session '%s' for container '%s' with shell '%s'",
                    exec.getId(), containerId, shell);
            return exec.getId();
        } catch (Exception e) {
            if (!"/bin/sh".equals(shell)) {
                Log.infof("Shell '%s' failed for container '%s', falling back to /bin/sh", shell, containerId);
                ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                        .withCmd("/bin/sh")
                        .withTty(true)
                        .withAttachStdin(true)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .exec();
                return exec.getId();
            }
            throw e;
        }
    }

    @Override
    public ExecSession startExecSession(String execId) throws Exception {
        PipedInputStream stdinPipe = new PipedInputStream(8192);
        PipedOutputStream stdinSink = new PipedOutputStream(stdinPipe);

        AtomicBoolean running = new AtomicBoolean(true);

        PipedInputStream stdoutPipe = new PipedInputStream(8192);
        PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

        ResultCallback.Adapter<Frame> callback = dockerClient.execStartCmd(execId)
                .withTty(true)
                .withStdIn(stdinPipe)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        try {
                            byte[] payload = frame.getPayload();
                            if (payload != null && payload.length > 0) {
                                stdoutSink.write(payload);
                                stdoutSink.flush();
                            }
                        } catch (IOException e) {
                            Log.warnf("Error writing terminal output: %s", e.getMessage());
                        }
                    }

                    @Override
                    public void onComplete() {
                        running.set(false);
                        try { stdoutSink.close(); } catch (IOException ignored) {}
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        running.set(false);
                        try { stdoutSink.close(); } catch (IOException ignored) {}
                        Log.warnf("Terminal exec error: %s", throwable.getMessage());
                    }
                });

        return new ExecSession() {
            @Override
            public OutputStream getStdin() {
                return stdinSink;
            }

            @Override
            public void onOutput(Consumer<byte[]> outputConsumer) {
                Thread.ofVirtual().name("terminal-output-" + execId).start(() -> {
                    try {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = stdoutPipe.read(buffer)) != -1) {
                            byte[] data = new byte[len];
                            System.arraycopy(buffer, 0, data, 0, len);
                            outputConsumer.accept(data);
                        }
                    } catch (IOException e) {
                        if (running.get()) {
                            Log.warnf("Terminal output reader error: %s", e.getMessage());
                        }
                    } finally {
                        running.set(false);
                    }
                });
            }

            @Override
            public boolean isRunning() {
                return running.get();
            }

            @Override
            public void close() {
                running.set(false);
                try { stdinSink.close(); } catch (IOException ignored) {}
                try { stdinPipe.close(); } catch (IOException ignored) {}
                try { stdoutSink.close(); } catch (IOException ignored) {}
                try { stdoutPipe.close(); } catch (IOException ignored) {}
                try { callback.close(); } catch (IOException ignored) {}
                Log.infof("Closed terminal exec session '%s'", execId);
            }
        };
    }

    @Override
    public void resizeExec(String execId, int cols, int rows) {
        try {
            dockerClient.resizeExecCmd(execId).withSize(rows, cols).exec();
        } catch (Exception e) {
            Log.warnf("Failed to resize exec '%s': %s", execId, e.getMessage());
        }
    }

    @Override
    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse info = dockerClient.inspectContainerCmd(containerId).exec();
            return info.getState() != null && Boolean.TRUE.equals(info.getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ContainerRuntimeInfo inspectContainer(String containerId) {
        try {
            InspectContainerResponse info = dockerClient.inspectContainerCmd(containerId).exec();
            boolean running = info.getState() != null && Boolean.TRUE.equals(info.getState().getRunning());
            String image = info.getConfig() != null ? info.getConfig().getImage() : null;
            String name = info.getName() != null ? info.getName().replaceFirst("^/", "") : null;
            return new ContainerRuntimeInfo(running, image, name);
        } catch (Exception e) {
            return ContainerRuntimeInfo.NOT_FOUND;
        }
    }

    @Override
    public void copyFileToContainer(String containerId, Path hostFile, String remotePath) {
        try {
            copyArchive(containerId, hostFile, remotePath);
        } catch (NotFoundException e) {
            // The Engine answers 404 to PUT /containers/{id}/archive when the destination
            // directory does not exist. Create it once and retry; a second 404 propagates.
            Log.infof("Destination '%s' missing in container '%s', creating it", remotePath, containerId);
            createDirectory(containerId, remotePath);
            copyArchive(containerId, hostFile, remotePath);
        }
        Log.infof("Copied file '%s' to container '%s' at '%s'", hostFile.getFileName(), containerId, remotePath);
    }

    private void copyArchive(String containerId, Path hostFile, String remotePath) {
        dockerClient.copyArchiveToContainerCmd(containerId)
                .withHostResource(hostFile.toAbsolutePath().toString())
                .withRemotePath(remotePath)
                .exec();
    }

    /**
     * {@code mkdir -p} as root, matching the privilege the archive copy itself runs with, so an
     * image whose USER cannot write to the parent still gets its upload directory.
     */
    private void createDirectory(String containerId, String remotePath) {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd("mkdir", "-p", remotePath)
                .withUser("root")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        StringBuilder output = new StringBuilder();
        try {
            dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            byte[] payload = frame.getPayload();
                            if (payload != null && output.length() < 1024) {
                                output.append(new String(payload, StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryCreationException(remotePath, "interrupted while waiting for mkdir");
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        if (exitCode == null || exitCode != 0) {
            throw new DirectoryCreationException(remotePath,
                    "mkdir exited with " + exitCode + " " + output.toString().trim());
        }
    }
}
