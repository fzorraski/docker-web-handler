package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.shared.ImageMatcher;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

/**
 * Hides infrastructure containers from the UI based on a configurable list of
 * image names ({@code hidden.images}). A hidden container is filtered out of
 * the container listing and its image out of the image listing and prune
 * candidates — the typical case being the application's own PostgreSQL
 * sidecar, which runs on the same daemon this tool manages but is not a
 * managed workload.
 *
 * <p>Matching follows the same rules as {@code protected.images} (see
 * {@link ImageMatcher}). Hidden images are also treated as protected, so a
 * hidden container cannot be stopped or removed by anyone who knows its id.</p>
 *
 * <p>When the list is empty the feature is fully disabled and incurs zero
 * Docker calls.</p>
 */
@ApplicationScoped
public class ContainerVisibilityService {

    @ConfigProperty(name = "hidden.images")
    Optional<List<String>> hiddenImages;

    /** Whether any hidden image is configured. */
    public boolean isEnabled() {
        return ImageMatcher.hasEntries(hiddenImages.orElse(null));
    }

    /** Returns true if the given image name matches a configured hidden image. */
    public boolean isHiddenImage(String image) {
        return isEnabled() && ImageMatcher.matches(hiddenImages.orElse(List.of()), image);
    }

    /**
     * Returns true if ANY of an image's repo tags is hidden. Docker gives no
     * ordering guarantee for {@code RepoTags}, so testing only the first entry
     * would leak an image that carries several tags (e.g. {@code postgres:17-alpine}
     * plus {@code postgres:latest} on the same digest).
     */
    public boolean isHiddenAnyTag(String[] repoTags) {
        if (!isEnabled() || repoTags == null) {
            return false;
        }
        for (String tag : repoTags) {
            if (isHiddenImage(tag)) {
                return true;
            }
        }
        return false;
    }
}
