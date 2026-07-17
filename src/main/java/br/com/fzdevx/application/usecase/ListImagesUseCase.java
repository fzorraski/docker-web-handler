package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.domain.model.DockerImage;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.docker.SelfContainerDetector;
import br.com.fzdevx.infrastructure.persistence.ImageUsageTracker;
import br.com.fzdevx.infrastructure.util.BytesConverter;
import br.com.fzdevx.infrastructure.util.DateFormatter;
import br.com.fzdevx.infrastructure.util.SanitizeHtml;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListImagesUseCase {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    @Inject
    DockerImagePort dockerImagePort;

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    ImageUsageTracker imageUsageTracker;

    public List<DockerImage> execute() {
        List<Image> allImages = dockerImagePort.listImages();
        List<Container> allContainers = dockerContainerPort.listContainers(true);

        // Detect own image ID to exclude from listing
        String selfImageId = SelfContainerDetector.findSelfImageId(allContainers);

        // Build set of image IDs currently in use by containers
        Set<String> usedImageIds = allContainers.stream()
                .map(Container::getImageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Count containers per image ID
        Map<String, Long> containerCounts = allContainers.stream()
                .map(Container::getImageId)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        // Update usage tracker: mark currently-in-use images with current timestamp
        imageUsageTracker.markInUse(usedImageIds);

        // Collect all existing image IDs for tracker cleanup
        Set<String> allImageIds = allImages.stream()
                .map(Image::getId)
                .collect(Collectors.toSet());
        imageUsageTracker.cleanup(allImageIds);

        // one bulk read instead of a per-image lookup inside the loop below;
        // listing degrades to "no usage data" on a store blip (prune must not)
        Map<String, Instant> lastUsedByImage;
        try {
            lastUsedByImage = imageUsageTracker.getAllLastUsed();
        } catch (RuntimeException e) {
            Log.warnf("Image usage data unavailable, listing without it: %s", e.getMessage());
            lastUsedByImage = Map.of();
        }

        // Build parent -> children map
        Map<String, List<String>> childrenMap = new HashMap<>();
        for (Image img : allImages) {
            String parentId = img.getParentId();
            if (parentId != null && !parentId.isEmpty()) {
                String childDisplay = img.getId().length() > 20 ? img.getId().substring(0, 20) : img.getId();
                childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(childDisplay);
            }
        }

        List<DockerImage> result = new ArrayList<>();

        for (Image img : allImages) {
            if (img.getRepoTags() == null || img.getRepoTags().length == 0) continue;

            String repoTag = img.getRepoTags()[0];
            String[] parts = repoTag.split(":");
            String repo = parts[0];

            if (repo.equals(Constants.DOCKER_WEB_HANDLER_IMAGE)) continue;
            if (selfImageId != null && img.getId().equals(selfImageId)) continue;

            String fullId = img.getId();
            boolean inUse = usedImageIds.contains(fullId);
            int count = containerCounts.getOrDefault(fullId, 0L).intValue();

            String parentId = img.getParentId();
            String parentDisplay = (parentId != null && !parentId.isEmpty())
                    ? (parentId.length() > 20 ? parentId.substring(0, 20) : parentId)
                    : "";

            List<String> children = childrenMap.getOrDefault(fullId, Collections.emptyList());

            // Determine last used display string
            Optional<Instant> lastUsed = Optional.ofNullable(lastUsedByImage.get(fullId));
            String lastUsedAt = lastUsed.map(DISPLAY_FORMAT::format).orElse(null);

            DockerImage dockerImage = new DockerImage();
            dockerImage.setRepository(repo);
            dockerImage.setTag(parts.length > 1 ? SanitizeHtml.html2text(parts[1]) : "-");
            dockerImage.setImageId(fullId.length() > 20 ? fullId.substring(0, 20) : fullId);
            dockerImage.setCreated(DateFormatter.convertSecondsToDate(img.getCreated()));
            dockerImage.setSize(BytesConverter.bytesToMegabytesFormatted(img.getSize(), 2));
            dockerImage.setInUse(inUse);
            dockerImage.setContainerCount(count);
            dockerImage.setParentId(parentDisplay);
            dockerImage.setChildIds(children);
            dockerImage.setLastUsedAt(lastUsedAt);

            result.add(dockerImage);
        }

        return result;
    }
}
