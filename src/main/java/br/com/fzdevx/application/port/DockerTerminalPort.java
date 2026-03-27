package br.com.fzdevx.application.port;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface DockerTerminalPort {

    String createExecSession(String containerId, String preferredShell) throws Exception;

    ExecSession startExecSession(String execId) throws Exception;

    void resizeExec(String execId, int cols, int rows);

    boolean isContainerRunning(String containerId);

    void copyFileToContainer(String containerId, Path hostFile, String remotePath);

    interface ExecSession extends AutoCloseable {
        OutputStream getStdin();
        void onOutput(Consumer<byte[]> outputConsumer);
        boolean isRunning();
    }
}
