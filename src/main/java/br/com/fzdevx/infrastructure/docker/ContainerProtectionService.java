package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.shared.ImageReference;
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

    @ConfigProperty(name = "protected.images")
    Optional<List<String>> protectedImages;

    /** Whether any protected image is configured. */
    public boolean isEnabled() {
        return protectedImages
                .map(list -> list.stream().anyMatch(s -> s != null && !s.isBlank()))
                .orElse(false);
    }

    /** Returns true if the given image name matches a configured protected image. */
    public boolean isProtectedImage(String image) {
        if (!isEnabled() || image == null || image.isBlank()) {
            return false;
        }
        String img = image.trim();
        String imgRepo = ImageReference.repository(img);
        String imgShort = ImageReference.shortName(img);
        for (String raw : protectedImages.orElse(List.of())) {
            if (raw == null) continue;
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            if (img.equals(entry)) {
                return true; // exact match including tag
            }
            if (!entry.contains(":")) {
                // entry has no tag — match any tag of this repository (full or short name)
                if (imgRepo.equals(entry) || imgShort.equals(entry)) {
                    return true;
                }
            }
        }
        return false;
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
