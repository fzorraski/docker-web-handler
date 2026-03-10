package br.com.fzdevx.usecase;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.service.ContainerExpirationService;
import com.github.dockerjava.api.DockerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.Consumer;

@ApplicationScoped
public class RemoveContainerUseCase {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerExpirationService expirationService;

    public void execute(String containerId, Consumer<ContainerEvent> eventSink) {
        // Step 1: Cancel expiration
        eventSink.accept(ContainerEvent.info("Cancelling", "Cancelling scheduled expiration..."));
        expirationService.cancel(containerId);
        eventSink.accept(ContainerEvent.info("Cancelling", "Expiration cancelled."));

        // Step 2: Stop container
        eventSink.accept(ContainerEvent.info("Stopping", "Stopping container " + containerId + "..."));
        try {
            dockerClient.stopContainerCmd(containerId).exec();
            eventSink.accept(ContainerEvent.info("Stopping", "Container stopped."));
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.info("Stopping", "Container was not running, skipping stop."));
        }

        // Step 3: Remove container
        eventSink.accept(ContainerEvent.info("Removing", "Removing container " + containerId + "..."));
        try {
            dockerClient.removeContainerCmd(containerId).exec();
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Removing", "Failed to remove container: " + e.getMessage()));
            return;
        }

        eventSink.accept(ContainerEvent.success("Complete", "Container " + containerId + " removed successfully."));
    }
}
