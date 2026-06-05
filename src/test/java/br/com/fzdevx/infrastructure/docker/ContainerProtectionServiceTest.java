package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerProtectionServiceTest {

    @Mock DockerClient dockerClient;
    @Mock ListContainersCmd listContainersCmd;

    private ContainerProtectionService service;

    @BeforeEach
    void setUp() {
        service = new ContainerProtectionService();
        service.dockerClient = dockerClient;
        service.protectedImages = Optional.empty();
    }

    private void configure(String... images) {
        service.protectedImages = Optional.of(List.of(images));
    }

    // ---- isEnabled ----

    @Test
    void isEnabled_emptyConfig_false() {
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabled_onlyBlankEntries_false() {
        configure("", "   ");
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabled_withEntry_true() {
        configure("nginx");
        assertTrue(service.isEnabled());
    }

    // ---- isProtectedImage: untagged entry matches any tag ----

    @Test
    void untaggedEntry_matchesAnyTag() {
        configure("nginx");
        assertTrue(service.isProtectedImage("nginx:latest"));
        assertTrue(service.isProtectedImage("nginx:1.27"));
        assertTrue(service.isProtectedImage("nginx"));
    }

    @Test
    void untaggedEntry_matchesDigestForm() {
        configure("nginx");
        assertTrue(service.isProtectedImage("nginx@sha256:0123456789abcdef"));
    }

    // ---- isProtectedImage: tagged entry matches only exact tag ----

    @Test
    void taggedEntry_matchesOnlyExactTag() {
        configure("nginx:1.27");
        assertTrue(service.isProtectedImage("nginx:1.27"));
        assertFalse(service.isProtectedImage("nginx:1.28"));
        assertFalse(service.isProtectedImage("nginx"));
    }

    // ---- isProtectedImage: namespace / registry handling ----

    @Test
    void fullRepository_matchesAnyTag() {
        configure("myorg/myapp");
        assertTrue(service.isProtectedImage("myorg/myapp:2.1"));
        assertTrue(service.isProtectedImage("myorg/myapp"));
    }

    @Test
    void shortName_matchesNamespacedImage() {
        configure("myapp");
        assertTrue(service.isProtectedImage("myorg/myapp:1.0"));
        assertTrue(service.isProtectedImage("registry.example.com/team/myapp:1.0"));
    }

    @Test
    void registryWithPort_tagStrippedCorrectly() {
        // The ':' in localhost:5000 is a registry port, not a tag separator.
        configure("myapp");
        assertTrue(service.isProtectedImage("localhost:5000/myapp:1.0"));
    }

    // ---- isProtectedImage: negatives ----

    @Test
    void nonMatchingImage_false() {
        configure("nginx");
        assertFalse(service.isProtectedImage("postgres:16"));
    }

    @Test
    void blankAndNullImage_false() {
        configure("nginx");
        assertFalse(service.isProtectedImage(null));
        assertFalse(service.isProtectedImage("  "));
    }

    @Test
    void featureDisabled_alwaysFalse() {
        assertFalse(service.isProtectedImage("nginx:latest"));
    }

    @Test
    void blankEntriesIgnored_realEntryStillMatches() {
        configure("", "nginx", "  ");
        assertTrue(service.isProtectedImage("nginx:1"));
    }

    // ---- isProtectedContainer ----

    @Test
    void isProtectedContainer_resolvesByShortIdAndChecksImage() {
        configure("nginx");
        Container c = mock(Container.class);
        when(c.getId()).thenReturn("abcdef1234567890ffff");
        when(c.getImage()).thenReturn("nginx:1.27");
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        assertTrue(service.isProtectedContainer("abcdef1234"));
    }

    @Test
    void isProtectedContainer_unprotectedImage_false() {
        configure("nginx");
        Container c = mock(Container.class);
        when(c.getId()).thenReturn("abcdef1234567890ffff");
        when(c.getImage()).thenReturn("postgres:16");
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        assertFalse(service.isProtectedContainer("abcdef1234"));
    }

    @Test
    void isProtectedContainer_disabled_noDockerCall() {
        // protectedImages empty -> feature off
        assertFalse(service.isProtectedContainer("abcdef1234"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void isProtectedContainer_unknownId_false() {
        configure("nginx");
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of());

        assertFalse(service.isProtectedContainer("doesnotexist"));
    }

    @Test
    void isProtectedContainer_dockerThrows_false() {
        configure("nginx");
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("daemon down"));

        assertFalse(service.isProtectedContainer("abcdef1234"));
    }
}
