package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerTerminalPort.DirectoryCreationException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DockerTerminalAdapterCopyFileTest {

    private static final String CONTAINER_ID = "abcdef1234567890";
    private static final String EXEC_ID = "exec-42";
    private static final Path HOST_FILE = Path.of("/tmp/host/clip.png");

    @Mock DockerClient dockerClient;
    @Mock CopyArchiveToContainerCmd copyCmd;
    @Mock ExecCreateCmd execCreateCmd;
    @Mock ExecStartCmd execStartCmd;
    @Mock InspectExecCmd inspectExecCmd;
    @Mock InspectExecResponse inspectExecResponse;

    @InjectMocks
    DockerTerminalAdapter adapter;

    @BeforeEach
    void setUp() {
        when(dockerClient.copyArchiveToContainerCmd(CONTAINER_ID)).thenReturn(copyCmd);
        when(copyCmd.withHostResource(any())).thenReturn(copyCmd);
        when(copyCmd.withRemotePath(any())).thenReturn(copyCmd);

        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn(EXEC_ID);
        when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(execCreateCmd);
        when(execCreateCmd.withCmd(any(String[].class))).thenReturn(execCreateCmd);
        when(execCreateCmd.withUser(any())).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStdout(anyBoolean())).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStderr(anyBoolean())).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenReturn(created);
        when(dockerClient.execStartCmd(EXEC_ID)).thenReturn(execStartCmd);
        when(dockerClient.inspectExecCmd(EXEC_ID)).thenReturn(inspectExecCmd);
        when(inspectExecCmd.exec()).thenReturn(inspectExecResponse);
    }

    /** Simulates docker streaming {@code stderr} and finishing, so awaitCompletion returns promptly. */
    private void mkdirExitsWith(long exitCode, String stderr) {
        when(execStartCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(() -> {});
            if (stderr != null) {
                callback.onNext(new Frame(StreamType.STDERR, stderr.getBytes(StandardCharsets.UTF_8)));
            }
            callback.onComplete();
            return callback;
        });
        when(inspectExecResponse.getExitCodeLong()).thenReturn(exitCode);
    }

    @Test
    void copy_existingDirectory_isASingleApiCall() {
        adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/tmp", true);

        verify(copyCmd).withHostResource(HOST_FILE.toAbsolutePath().toString());
        verify(copyCmd).withRemotePath("/tmp");
        verify(copyCmd, times(1)).exec();
        verify(dockerClient, never()).execCreateCmd(any());
    }

    @Test
    void copy_withoutCreateFlag_neverRunsMkdir() {
        doThrow(new NotFoundException("Could not find the file /root/.ssh")).when(copyCmd).exec();

        assertThrows(NotFoundException.class, () -> adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/root/.ssh"));

        verify(copyCmd, times(1)).exec();
        verify(dockerClient, never()).execCreateCmd(any());
    }

    @Test
    void copy_missingDirectory_createsItAsRootAndRetriesOnce() {
        doThrow(new NotFoundException("Could not find the file /tmp/attachments")).doNothing().when(copyCmd).exec();
        mkdirExitsWith(0, null);

        adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/tmp/attachments", true);

        verify(copyCmd, times(2)).exec();
        verify(execCreateCmd).withCmd("mkdir", "-p", "/tmp/attachments");
        verify(execCreateCmd).withUser("root");
        verify(execCreateCmd).withAttachStderr(true);
    }

    @Test
    void copy_mkdirFails_throwsWithStderrAndDoesNotRetry() {
        doThrow(new NotFoundException("missing")).when(copyCmd).exec();
        mkdirExitsWith(1, "mkdir: cannot create directory '/opt/x': Permission denied");

        DirectoryCreationException ex = assertThrows(DirectoryCreationException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/opt/x", true));

        assertEquals("/opt/x", ex.getDirectory());
        assertTrue(ex.getMessage().contains("Permission denied"), ex.getMessage());
        verify(copyCmd, times(1)).exec();
    }

    @Test
    void copy_mkdirHangs_reportsTimeoutNotNullExitCode() {
        adapter.mkdirTimeoutMillis = 50;
        doThrow(new NotFoundException("missing")).when(copyCmd).exec();
        when(execStartCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onStart(() -> {});
            return callback; // never completes
        });

        DirectoryCreationException ex = assertThrows(DirectoryCreationException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/mnt/slow", true));

        assertTrue(ex.getMessage().contains("did not finish within 50 ms"), ex.getMessage());
        verify(inspectExecCmd, never()).exec();
    }

    @Test
    void copy_containerGoneBetweenCheckAndCopy_propagatesNotFoundFromExec() {
        doThrow(new NotFoundException("No such container")).when(copyCmd).exec();
        when(execCreateCmd.exec()).thenThrow(new NotFoundException("No such container: " + CONTAINER_ID));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/tmp", true));

        assertTrue(ex.getMessage().contains("No such container"), ex.getMessage());
        verify(copyCmd, times(1)).exec();
        verify(dockerClient, never()).execStartCmd(any());
    }

    @Test
    void copy_otherFailure_propagatesWithoutMkdir() {
        doThrow(new RuntimeException("daemon unavailable")).when(copyCmd).exec();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, HOST_FILE, "/tmp", true));

        assertEquals("daemon unavailable", ex.getMessage());
        verify(dockerClient, never()).execCreateCmd(any());
    }
}
