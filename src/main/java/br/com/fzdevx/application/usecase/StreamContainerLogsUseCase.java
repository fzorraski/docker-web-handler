package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.InputValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@ApplicationScoped
public class StreamContainerLogsUseCase {

    private static final int DEFAULT_TAIL = 1000;

    @Inject
    DockerContainerPort dockerContainerPort;

    public void execute(String containerId, Consumer<ContainerEvent> eventSink, Supplier<Boolean> isActive) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Logs", idError.get()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Logs", "Connecting to container logs..."));
        dockerContainerPort.streamLogs(containerId, DEFAULT_TAIL, eventSink, isActive);
        eventSink.accept(ContainerEvent.success("Logs", "Log stream ended."));
    }
}
