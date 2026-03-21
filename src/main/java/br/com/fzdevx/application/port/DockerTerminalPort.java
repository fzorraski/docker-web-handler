package br.com.fzdevx.application.port;

import java.io.OutputStream;
import java.util.function.Consumer;

public interface DockerTerminalPort {

    String createExecSession(String containerId, String preferredShell) throws Exception;

    ExecSession startExecSession(String execId) throws Exception;

    void resizeExec(String execId, int cols, int rows);

    boolean isContainerRunning(String containerId);

    interface ExecSession extends AutoCloseable {
        OutputStream getStdin();
        void onOutput(Consumer<byte[]> outputConsumer);
        boolean isRunning();
    }
}
