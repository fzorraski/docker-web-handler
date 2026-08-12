package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.shared.ImageMatcher;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * Marks containers as "protected" based on a configurable list of image names
 * ({@code protected.images}). A protected container cannot be stopped, removed,
 * or deleted — neither manually (UI / CI API) nor automatically (expiration /
 * schedules).
 *
 * <p>Matching is by image. An entry without a tag (e.g. {@code nginx}) protects
 * every tag of that image; an entry with a tag (e.g. {@code nginx:1.27}) protects
 * only that exact tag. A registry/namespace-qualified image also matches by its
 * short (last-segment) name, mirroring the {@code repository.port-paths} fallback.</p>
 *
 * <p>When the list is empty the feature is fully disabled and incurs zero Docker
 * calls.</p>
 */
@ApplicationScoped
public class ContainerProtectionService {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerVisibilityService visibilityService;

    @ConfigProperty(name = "protected.images")
    Optional<List<String>> protectedImages;

    /** Whether any protected image is configured. */
    public boolean isEnabled() {
        return ImageMatcher.hasEntries(protectedImages.orElse(null)) || visibilityService.isEnabled();
    }

    /**
     * Returns true if the given image name matches a configured protected
     * image. Hidden images ({@code hidden.images}) are protected as well: a
     * container nobody can see must not be stoppable or removable by anyone
     * who happens to know its id.
     */
    public boolean isProtectedImage(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        return ImageMatcher.matches(protectedImages.orElse(List.of()), image)
                || visibilityService.isHiddenImage(image);
    }

    /**
     * Resolves a container (by full or short id) to its image and checks protection.
     * Returns false if protection is disabled or the container can't be resolved.
     */
    public boolean isProtectedContainer(String containerId) {
        if (!isEnabled() || containerId == null || containerId.isBlank()) {
            return false;
        }
        try {
            for (Container c : dockerClient.listContainersCmd().withShowAll(true).exec()) {
                if (c.getId().startsWith(containerId)) {
                    return isProtectedImage(c.getImage());
                }
            }
        } catch (Exception e) {
            Log.warnf("Failed to resolve container %s for protection check: %s", containerId, e.getMessage());
        }
        return false;
    }
}
