package br.com.fzdevx.usecase;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.util.InputValidator;
import com.github.dockerjava.api.DockerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
public class RemoveImageUseCase {

    @Inject
    DockerClient dockerClient;

    public void execute(String imageId, Consumer<ContainerEvent> eventSink) {
        Optional<String> idError = InputValidator.validateImageId(imageId);
        if (idError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Removing", idError.get()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Removing", "Removing image " + imageId + "..."));

        try {
            dockerClient.removeImageCmd(imageId).exec();
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

        eventSink.accept(ContainerEvent.success("Complete", "Image " + imageId + " removed successfully."));
    }
}
