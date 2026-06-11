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

    void copyFileToContainer(String containerId, Path hostFile, String remotePath);

    /** Lightweight snapshot of a container from a single inspect call. */
    record ContainerRuntimeInfo(boolean running, String image) {
        public static final ContainerRuntimeInfo NOT_FOUND = new ContainerRuntimeInfo(false, null);
    }

    interface ExecSession extends AutoCloseable {
        OutputStream getStdin();
        void onOutput(Consumer<byte[]> outputConsumer);
        boolean isRunning();
    }
}
