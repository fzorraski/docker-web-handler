package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DockerTerminalAdapterCopyFileTest {

    private static final String CONTAINER_ID = "abcdef1234567890";
    private static final byte[] CONTENT = "png-bytes".getBytes();

    @Mock DockerClient dockerClient;
    @Mock CopyArchiveToContainerCmd copyCmd;

    @InjectMocks
    DockerTerminalAdapter adapter;

    @TempDir Path tempDir;
    Path hostFile;

    /** Directories the fake Engine considers present; a PUT anywhere else answers 404. */
    final Set<String> existingDirs = new HashSet<>(List.of("/", "/tmp"));
    /** Every attempt the adapter made: extraction directory plus the tar entry it carried. */
    final List<Attempt> attempts = new ArrayList<>();
    record Attempt(String extractAt, String entryName, byte[] content, int mode) {}

    @BeforeEach
    void setUp() throws Exception {
        hostFile = tempDir.resolve("clip.png");
        Files.write(hostFile, CONTENT);

        when(dockerClient.copyArchiveToContainerCmd(CONTAINER_ID)).thenReturn(copyCmd);
        ArgumentCaptor<InputStream> tarStream = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<String> remotePath = ArgumentCaptor.forClass(String.class);
        when(copyCmd.withTarInputStream(tarStream.capture())).thenReturn(copyCmd);
        when(copyCmd.withRemotePath(remotePath.capture())).thenReturn(copyCmd);
        doAnswer(invocation -> {
            String at = remotePath.getValue();
            try (TarArchiveInputStream in = new TarArchiveInputStream(tarStream.getValue())) {
                TarArchiveEntry entry = in.getNextEntry();
                attempts.add(new Attempt(at, entry.getName(), in.readAllBytes(), entry.getMode()));
            }
            if (!existingDirs.contains(at)) throw new NotFoundException("Could not find the file " + at);
            return null;
        }).when(copyCmd).exec();
    }

    @Test
    void existingDirectory_isASingleBareCopyAtThatDirectory() {
        adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/tmp", true);

        assertEquals(1, attempts.size());
        assertEquals("/tmp", attempts.getFirst().extractAt());
        assertEquals("clip.png", attempts.getFirst().entryName());
        assertArrayEquals(CONTENT, attempts.getFirst().content());
        assertEquals(0100644, attempts.getFirst().mode());
        verify(dockerClient, never()).execCreateCmd(any());
    }

    @Test
    void missingDirectory_withCreateFlag_extractsAtNearestExistingAncestor() {
        adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/tmp/attachments/today", true);

        assertEquals(List.of("/tmp/attachments/today", "/tmp/attachments", "/tmp"),
                attempts.stream().map(Attempt::extractAt).toList());
        assertEquals("attachments/today/clip.png", attempts.getLast().entryName());
    }

    @Test
    void missingDirectory_withCreateFlag_fallsBackToRootWhenNothingElseExists() {
        adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/workspace/attachments", true);

        assertEquals("/", attempts.getLast().extractAt());
        assertEquals("workspace/attachments/clip.png", attempts.getLast().entryName());
    }

    @Test
    void missingDirectory_withoutCreateFlag_propagatesAfterOneAttempt() {
        assertThrows(NotFoundException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/root/.ssh"));

        assertEquals(1, attempts.size());
        assertEquals("/root/.ssh", attempts.getFirst().extractAt());
    }

    @Test
    void missingEverything_evenRoot_propagates() {
        existingDirs.clear();

        assertThrows(NotFoundException.class,
                () -> adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/a/b", true));

        assertEquals(List.of("/a/b", "/a", "/"), attempts.stream().map(Attempt::extractAt).toList());
    }

    @Test
    void stagedTarIsAlwaysRemoved() throws Exception {
        adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/tmp", true);
        doThrow(new RuntimeException("daemon unavailable")).when(copyCmd).exec();
        assertThrows(RuntimeException.class, () -> adapter.copyFileToContainer(CONTAINER_ID, hostFile, "/tmp", true));

        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertTrue(files.map(p -> p.getFileName().toString()).noneMatch(n -> n.startsWith("container-copy-")));
        }
    }
}
