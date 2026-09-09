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

    /**
     * Copies {@code hostFile} into {@code remotePath} inside the container, creating the
     * directory (and parents) first when it does not exist yet.
     *
     * @throws DirectoryCreationException when the directory is missing and cannot be created
     */
    void copyFileToContainer(String containerId, Path hostFile, String remotePath);

    /** The destination directory did not exist and {@code mkdir -p} inside the container failed. */
    final class DirectoryCreationException extends RuntimeException {
        private final String directory;

        public DirectoryCreationException(String directory, String detail) {
            super("Could not create directory '" + directory + "' inside the container: " + detail);
            this.directory = directory;
        }

        public String getDirectory() { return directory; }
    }

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
