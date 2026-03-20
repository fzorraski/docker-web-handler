package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerStats;
import br.com.fzdevx.domain.shared.InputValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@ApplicationScoped
public class StreamContainerStatsUseCase {

    @Inject
    DockerContainerPort dockerContainerPort;

    public void execute(String containerId, Consumer<ContainerStats> statsSink, Supplier<Boolean> isActive) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            return;
        }
        dockerContainerPort.streamStats(containerId, statsSink, isActive);
    }
}
