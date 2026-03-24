package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.DockerImagePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.docker.SelfContainerDetector;
import br.com.fzdevx.infrastructure.persistence.ImageUsageTracker;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.util.BytesConverter;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class PruneImagesUseCase {

    @Inject
    DockerImagePort dockerImagePort;

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    ImageUsageTracker imageUsageTracker;

    @Inject
    ResourceCounterService resourceCounterService;

    public void execute(int minDays, Consumer<ContainerEvent> eventSink) {
        eventSink.accept(ContainerEvent.info("Validating", "Analyzing images..."));

        try {
            List<Image> allImages = dockerImagePort.listImages();
            List<Container> allContainers = dockerContainerPort.listContainers(true);

            Set<String> usedImageIds = allContainers.stream()
                    .map(Container::getImageId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // Detect own image ID to protect from pruning
            String selfImageId = SelfContainerDetector.findSelfImageId(allContainers);

            boolean pruneAll = (minDays <= 0);
            Instant cutoff = pruneAll ? null : Instant.now().minus(minDays, ChronoUnit.DAYS);

            List<Image> toRemove = new ArrayList<>();
            for (Image img : allImages) {
                if (img.getRepoTags() != null && img.getRepoTags().length > 0) {
                    String repo = img.getRepoTags()[0].split(":")[0];
                    if (repo.equals(Constants.DOCKER_WEB_HANDLER_IMAGE)) continue;
                }
                if (selfImageId != null && img.getId().equals(selfImageId)) continue;

                // Skip images currently in use
                if (usedImageIds.contains(img.getId())) continue;

                if (pruneAll) {
                    toRemove.add(img);
                } else {
                    // Check last-used timestamp from tracker
                    Optional<Instant> lastUsed = imageUsageTracker.getLastUsed(img.getId());

                    if (lastUsed.isPresent()) {
                        if (lastUsed.get().isBefore(cutoff)) {
                            toRemove.add(img);
                        }
                    } else {
                        // Never tracked: fall back to image creation date
                        Instant created = Instant.ofEpochSecond(img.getCreated());
                        if (created.isBefore(cutoff)) {
                            toRemove.add(img);
                        }
                    }
                }
            }

            if (toRemove.isEmpty()) {
                String noImagesMsg = pruneAll
                        ? "No unused images found."
                        : "No unused images idle for more than " + minDays + " day(s) found.";
                eventSink.accept(ContainerEvent.success("Pruning", noImagesMsg));
                return;
            }

            eventSink.accept(ContainerEvent.info("Pruning",
                    "Removing " + toRemove.size() + " unused image(s)..."));

            int removed = 0;
            long totalSize = 0;
            List<String> errors = new ArrayList<>();

            for (Image img : toRemove) {
                String displayId = img.getId().length() > 20 ? img.getId().substring(0, 20) : img.getId();
                try {
                    dockerImagePort.removeImage(img.getId());
                    resourceCounterService.increment(ResourceCounterService.IMAGES_DELETED);
                    removed++;
                    totalSize += img.getSize();
                    eventSink.accept(ContainerEvent.progress("Pruning",
                            "Removed " + displayId + " (" + removed + "/" + toRemove.size() + ")",
                            (int) ((removed * 100L) / toRemove.size())));
                } catch (Exception e) {
                    errors.add(displayId + ": " + e.getMessage());
                }
            }

            String formatted = BytesConverter.bytesToMegabytesFormatted(totalSize, 2);
            String msg = "Removed " + removed + " image(s). Space reclaimed: " + formatted;
            if (!errors.isEmpty()) {
                msg += ". Skipped " + errors.size() + " image(s) due to errors.";
            }
            eventSink.accept(ContainerEvent.success("Pruning", msg));
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Pruning", "Prune failed: " + e.getMessage()));
        }
    }
}
