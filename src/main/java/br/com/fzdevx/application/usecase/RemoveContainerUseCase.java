package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import br.com.fzdevx.domain.shared.InputValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
public class RemoveContainerUseCase {

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ContainerSchedulingService schedulingService;

    public void execute(String containerId, Consumer<ContainerEvent> eventSink) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Cancelling", idError.get()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Cancelling", "Cancelling scheduled expiration..."));
        expirationService.remove(containerId);
        eventSink.accept(ContainerEvent.info("Cancelling", "Expiration cancelled."));

        eventSink.accept(ContainerEvent.info("Stopping", "Stopping container " + containerId + "..."));
        try {
            dockerContainerPort.stopContainer(containerId);
            eventSink.accept(ContainerEvent.info("Stopping", "Container stopped."));
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.info("Stopping", "Container was not running, skipping stop."));
        }

        eventSink.accept(ContainerEvent.info("Removing", "Removing container " + containerId + "..."));
        try {
            dockerContainerPort.removeContainer(containerId);
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Removing", "Failed to remove container: " + e.getMessage()));
            return;
        }

        schedulingService.removeSchedulesByContainer(containerId);

        eventSink.accept(ContainerEvent.success("Complete", "Container " + containerId + " removed successfully."));
    }
}
