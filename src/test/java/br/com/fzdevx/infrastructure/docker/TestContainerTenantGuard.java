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
        return with(tenantVisibility, dockerClient, disabledVisibility());
    }

    public static ContainerTenantGuard with(TenantVisibility tenantVisibility, DockerClient dockerClient,
                                            ContainerVisibilityService visibilityService) {
        ContainerTenantGuard guard = new ContainerTenantGuard();
        guard.tenantVisibility = tenantVisibility;
        guard.dockerClient = dockerClient;
        guard.visibilityService = visibilityService;
        guard.tenantSharing = new br.com.fzdevx.infrastructure.config.TenantSharing();
        return guard;
    }

    /** No hidden images configured — the feature is off, no extra inspect. */
    public static ContainerVisibilityService disabledVisibility() {
        ContainerVisibilityService service = new ContainerVisibilityService();
        service.hiddenImages = java.util.Optional.empty();
        return service;
    }

    /** Hides the given images (same syntax as the {@code hidden.images} property). */
    public static ContainerVisibilityService hiding(String... images) {
        ContainerVisibilityService service = new ContainerVisibilityService();
        service.hiddenImages = java.util.Optional.of(java.util.List.of(images));
        return service;
    }
}
