package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.TenantVisibility;
import br.com.fzdevx.infrastructure.config.TestTenantVisibility;
import br.com.fzdevx.domain.model.auth.Permission;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A container the owning tenant shared reaches the same by-id paths its own
 * tenant does - this guard is the only gate on all of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerTenantGuardSharingTest {

    private static final String CONTAINER = "abcdef123456";

    @Mock
    TenantRepository tenantRepository;

    private DockerClient dockerClientWithLabels(Map<String, String> labels) {
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getImage()).thenReturn("mywms-spk:1.0");
        when(config.getLabels()).thenReturn(labels);

        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getConfig()).thenReturn(config);

        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        when(cmd.exec()).thenReturn(response);

        DockerClient client = mock(DockerClient.class);
        when(client.inspectContainerCmd(CONTAINER)).thenReturn(cmd);
        return client;
    }

    private TenantVisibility memberOf(String... tenantIds) {
        CurrentUser user = new CurrentUser();
        user.set("u1", "alice", Set.of(Permission.CONTAINERS_VIEW),
                new LinkedHashSet<>(List.of(tenantIds)));
        return TestTenantVisibility.forUser(user, tenantRepository);
    }

    private ContainerTenantGuard guardFor(TenantVisibility visibility, Map<String, String> labels) {
        return TestContainerTenantGuard.with(visibility, dockerClientWithLabels(labels));
    }

    @Test
    void sharedContainer_isVisibleToTheSharedTenant() {
        assertTrue(guardFor(memberOf("t2"), Map.of(
                Constants.TENANT_LABEL, "t1",
                Constants.SHARED_TENANTS_LABEL, "t2,t3")).canSee(CONTAINER));
    }

    @Test
    void unsharedContainer_staysInvisible() {
        assertFalse(guardFor(memberOf("t9"), Map.of(
                Constants.TENANT_LABEL, "t1",
                Constants.SHARED_TENANTS_LABEL, "t2,t3")).canSee(CONTAINER));
    }

    @Test
    void ownerStillSeesItsOwnContainer() {
        assertTrue(guardFor(memberOf("t1"), Map.of(
                Constants.TENANT_LABEL, "t1",
                Constants.SHARED_TENANTS_LABEL, "t2")).canSee(CONTAINER));
    }

    @Test
    void containerWithoutTheSharedLabel_behavesAsBefore() {
        assertTrue(guardFor(memberOf("t1"), Map.of(Constants.TENANT_LABEL, "t1")).canSee(CONTAINER));
        assertFalse(guardFor(memberOf("t2"), Map.of(Constants.TENANT_LABEL, "t1")).canSee(CONTAINER));
    }

    @Test
    void whitespaceInTheLabelIsTolerated() {
        // the label is a hand-joinable string; a stray space must not hide it
        assertTrue(guardFor(memberOf("t3"), Map.of(
                Constants.TENANT_LABEL, "t1",
                Constants.SHARED_TENANTS_LABEL, "t2, t3")).canSee(CONTAINER));
    }
}
