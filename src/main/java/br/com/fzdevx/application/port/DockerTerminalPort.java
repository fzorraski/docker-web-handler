package br.com.fzdevx.application.port;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface DockerTerminalPort {

    String createExecSession(String containerId, String preferredShell) throws Exception;

    ExecSession startExecSession(String execId) throws Exception;

    void resizeExec(String execId, int cols, int rows);

    boolean isContainerRunning(String containerId);

    /**
     * Inspects a container once, returning both its running state and image reference.
     * Used to avoid a second inspect round-trip when both are needed.
     */
    ContainerRuntimeInfo inspectContainer(String containerId);

    /** Copies {@code hostFile} into the existing directory {@code remotePath} inside the container. */
    default void copyFileToContainer(String containerId, Path hostFile, String remotePath) {
        copyFileToContainer(containerId, hostFile, remotePath, false);
    }

    /**
     * Copies {@code hostFile} into {@code remotePath} inside the container. With
     * {@code createMissingDirectory} the file is delivered as an archive entry that carries the
     * directory path, so the Engine creates missing parents (as root) while extracting; only pass
     * {@code true} for administrator-configured paths, never for a path chosen by the requesting
     * user. Without it a missing directory fails the copy.
     */
    void copyFileToContainer(String containerId, Path hostFile, String remotePath, boolean createMissingDirectory);

    /** Lightweight snapshot of a container from a single inspect call. */
    record ContainerRuntimeInfo(boolean running, String image, String name) {
        public static final ContainerRuntimeInfo NOT_FOUND = new ContainerRuntimeInfo(false, null, null);
    }

    interface ExecSession extends AutoCloseable {
        OutputStream getStdin();
        void onOutput(Consumer<byte[]> outputConsumer);
        boolean isRunning();
    }
}
