package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerTerminalPort;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.async.ResultCallback;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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
    public void copyFileToContainer(String containerId, Path hostFile, String remotePath, boolean createMissingDirectory) {
        String fileName = hostFile.getFileName().toString();
        Path tar = null;
        try {
            tar = Files.createTempFile("container-copy-", ".tar");
            // First try the directory itself: extracting there keeps working on a read-only
            // rootfs when the directory is a writable volume or tmpfs mount.
            try {
                send(containerId, hostFile, tar, remotePath, fileName);
                return;
            } catch (NotFoundException e) {
                if (!createMissingDirectory) throw e;
            }
            // The Engine creates every missing parent of an archive entry while extracting, so
            // walk up to the nearest existing ancestor and carry the remainder in the entry name.
            List<String> segments = Arrays.stream(remotePath.split("/")).filter(seg -> !seg.isEmpty()).toList();
            for (int keep = segments.size() - 1; keep >= 0; keep--) {
                String extractAt = "/" + String.join("/", segments.subList(0, keep));
                String entryName = String.join("/", segments.subList(keep, segments.size())) + "/" + fileName;
                try {
                    send(containerId, hostFile, tar, extractAt, entryName);
                    return;
                } catch (NotFoundException e) {
                    if (keep == 0) throw e;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage '" + fileName + "' for container " + containerId, e);
        } finally {
            if (tar != null) {
                try { Files.deleteIfExists(tar); } catch (IOException ignored) {}
            }
        }
    }

    private void send(String containerId, Path hostFile, Path tar, String extractAt, String entryName) throws IOException {
        writeSingleEntryTar(hostFile, entryName, tar);
        try (InputStream in = Files.newInputStream(tar)) {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(in)
                    .withRemotePath(extractAt)
                    .exec();
        }
        Log.infof("Copied file '%s' to container '%s' at '%s'", entryName, containerId, extractAt);
    }

    /** Uncompressed tar with one regular file entry (mode 0644); images do not compress, and the Engine only needs a tar. */
    private static void writeSingleEntryTar(Path file, String entryName, Path tar) throws IOException {
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(Files.size(file));
            entry.setMode(0100644);
            entry.setModTime(Files.getLastModifiedTime(file).toMillis());
            out.putArchiveEntry(entry);
            Files.copy(file, out);
            out.closeArchiveEntry();
        }
    }

}
