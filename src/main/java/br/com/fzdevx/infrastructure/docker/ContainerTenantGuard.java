package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.exception.EntityNotFoundException;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.config.TenantVisibility;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Visibility guard for by-id container operations. Every by-id path (terminal
 * authorization, file upload, log streaming/analysis, SSE) goes through here,
 * so this is the single place that decides whether a caller may address a
 * container at all.
 *
 * <p>Two rules apply. A container running a {@code hidden.images} image is
 * invisible to <em>everyone</em>, bypass or not — it is infrastructure (e.g.
 * the app's own database sidecar), not a managed workload. A container's
 * tenant then lives in a docker label, so checking it needs an inspect -
 * skipped entirely when the caller bypasses tenant filtering (RBAC off,
 * TENANTS_VIEW_ALL, workers) and no hidden image is configured.</p>
 */
@ApplicationScoped
public class ContainerTenantGuard {

    @Inject
    DockerClient dockerClient;

    @Inject
    TenantVisibility tenantVisibility;

    @Inject
    ContainerVisibilityService visibilityService;

    public void requireVisible(String containerId) {
        if (!canSee(containerId)) {
            throw new EntityNotFoundException("Container not found.");
        }
    }

    public boolean canSee(String containerId) {
        boolean checkHidden = visibilityService != null && visibilityService.isEnabled();
        if (!checkHidden && tenantVisibility.bypass()) {
            return true;
        }
        InspectContainerResponse info;
        try {
            // one inspect serves both checks
            info = dockerClient.inspectContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            return false;
        }
        ContainerConfig config = info.getConfig();
        if (checkHidden && config != null && visibilityService.isHiddenImage(config.getImage())) {
            return false; // infrastructure container: invisible even to bypassing callers
        }
        if (tenantVisibility.bypass()) {
            return true;
        }
        Map<String, String> labels = config == null ? null : config.getLabels();
        String tenantId = labels == null ? null : labels.get(Constants.TENANT_LABEL);
        return tenantVisibility.canSee(tenantId);
    }
}
