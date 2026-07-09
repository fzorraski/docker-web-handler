package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.config.TenantVisibility;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Tenant guard for by-id container operations. A container's tenant lives in
 * a docker label, so checking it needs an inspect - skipped entirely when the
 * caller bypasses tenant filtering (RBAC off, TENANTS_VIEW_ALL, workers).
 */
@ApplicationScoped
public class ContainerTenantGuard {

    @Inject
    DockerClient dockerClient;

    @Inject
    TenantVisibility tenantVisibility;

    public void requireVisible(String containerId) {
        if (!canSee(containerId)) {
            throw new EntityNotFoundException("Container not found.");
        }
    }

    public boolean canSee(String containerId) {
        if (tenantVisibility.bypass()) {
            return true;
        }
        String tenantId;
        try {
            Map<String, String> labels = dockerClient.inspectContainerCmd(containerId)
                    .exec().getConfig().getLabels();
            tenantId = labels == null ? null : labels.get(Constants.TENANT_LABEL);
        } catch (NotFoundException e) {
            return false;
        }
        return tenantVisibility.canSee(tenantId);
    }
}
