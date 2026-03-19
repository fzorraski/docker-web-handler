package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerImagePort; // ⚠ SOLID — DIP: depends on port, not DockerClient
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
public class RemoveImageUseCase {

    @Inject
    DockerImagePort dockerImagePort; // ⚠ SOLID — DIP: injecting port interface

    @Inject
    ResourceCounterService resourceCounterService;

    public void execute(String imageId, Consumer<ContainerEvent> eventSink) {
        Optional<String> idError = InputValidator.validateImageId(imageId);
        if (idError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Removing", idError.get()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Removing", "Removing image " + imageId + "..."));

        try {
            dockerImagePort.removeImage(imageId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("image is being used")) {
                eventSink.accept(ContainerEvent.error("Removing",
                        "Cannot remove: image is currently in use by a container."));
            } else if (msg != null && msg.contains("image has dependent child images")) {
                eventSink.accept(ContainerEvent.error("Removing",
                        "Cannot remove: image has dependent child images."));
            } else {
                eventSink.accept(ContainerEvent.error("Removing",
                        "Failed to remove image: " + msg));
            }
            return;
        }

        resourceCounterService.increment(ResourceCounterService.IMAGES_DELETED);
        eventSink.accept(ContainerEvent.success("Complete", "Image " + imageId + " removed successfully."));
    }
}
