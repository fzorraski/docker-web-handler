package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.infrastructure.config.TestTenantVisibility;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A container running a hidden image must be unreachable through EVERY by-id
 * path (terminal ticket, file upload, log streaming/analysis), not just the
 * stop/remove paths guarded by {@link ContainerProtectionService}. All of them
 * funnel through {@link ContainerTenantGuard}, so the rule is enforced here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerTenantGuardHiddenTest {

    private static final String DB_CONTAINER = "abcdef123456";

    private DockerClient dockerClientReturning(String image) {
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getImage()).thenReturn(image);
        when(config.getLabels()).thenReturn(null);

        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getConfig()).thenReturn(config);

        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        when(cmd.exec()).thenReturn(response);

        DockerClient client = mock(DockerClient.class);
        when(client.inspectContainerCmd(DB_CONTAINER)).thenReturn(cmd);
        return client;
    }

    @Test
    void hiddenContainer_invisible_evenToBypassingCaller() {
        // bypass = RBAC off / TENANTS_VIEW_ALL — still must not reach the sidecar
        ContainerTenantGuard guard = TestContainerTenantGuard.with(
                TestTenantVisibility.passthrough(),
                dockerClientReturning("postgres:17-alpine"),
                TestContainerTenantGuard.hiding("postgres:17-alpine"));

        assertFalse(guard.canSee(DB_CONTAINER));
        assertThrows(EntityNotFoundException.class, () -> guard.requireVisible(DB_CONTAINER));
    }

    @Test
    void managedContainer_stillVisible_whenHidingIsConfigured() {
        ContainerTenantGuard guard = TestContainerTenantGuard.with(
                TestTenantVisibility.passthrough(),
                dockerClientReturning("mywms-spk:1.0"),
                TestContainerTenantGuard.hiding("postgres:17-alpine"));

        assertTrue(guard.canSee(DB_CONTAINER));
    }

    @Test
    void featureDisabled_bypassingCaller_needsNoInspect() {
        DockerClient client = mock(DockerClient.class);
        ContainerTenantGuard guard = TestContainerTenantGuard.with(
                TestTenantVisibility.passthrough(), client);

        assertTrue(guard.canSee(DB_CONTAINER));
        verifyNoInteractions(client);
    }
}
