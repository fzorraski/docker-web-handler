package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.infrastructure.config.TenantVisibility;
import br.com.fzdevx.infrastructure.config.TestTenantVisibility;
import com.github.dockerjava.api.DockerClient;

/**
 * Test wiring helpers for {@link ContainerTenantGuard} (package-private
 * injected fields).
 */
public final class TestContainerTenantGuard {

    private TestContainerTenantGuard() {
    }

    /** Legacy mode: bypasses without touching the docker client. */
    public static ContainerTenantGuard passthrough() {
        return with(TestTenantVisibility.passthrough(), null);
    }

    public static ContainerTenantGuard with(TenantVisibility tenantVisibility, DockerClient dockerClient) {
        ContainerTenantGuard guard = new ContainerTenantGuard();
        guard.tenantVisibility = tenantVisibility;
        guard.dockerClient = dockerClient;
        return guard;
    }
}
