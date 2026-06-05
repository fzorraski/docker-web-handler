package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.ContainerSchedulingService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
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
    ContainerProtectionService protectionService;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ContainerSchedulingService schedulingService;

    @Inject
    DatabaseService databaseService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    public void execute(String containerId, Consumer<ContainerEvent> eventSink) {
        execute(containerId, false, null, null, eventSink);
    }

    public void execute(String containerId, boolean deleteDatabase,
                        String repository, String databaseName,
                        Consumer<ContainerEvent> eventSink) {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Cancelling", idError.get()));
            return;
        }

        if (protectionService.isProtectedContainer(containerId)) {
            eventSink.accept(ContainerEvent.error("Removing",
                    "Container is protected and cannot be removed."));
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

        if (deleteDatabase && repository != null && databaseName != null) {
            eventSink.accept(ContainerEvent.info("Dropping Database",
                    "Dropping database '" + databaseName + "'..."));
            try {
                boolean isProtected = managedDatabaseRepository.find(repository, databaseName)
                        .map(ManagedDatabase::isProtectedFlag).orElse(false);
                if (isProtected) {
                    eventSink.accept(ContainerEvent.info("Dropping Database",
                            "Database '" + databaseName + "' is protected, skipping deletion."));
                } else {
                    databaseService.dropDatabase(repository, databaseName);
                    eventSink.accept(ContainerEvent.info("Dropping Database",
                            "Database '" + databaseName + "' dropped successfully."));
                    expirationService.removeContainersByDatabase(databaseName, containerId);
                }
            } catch (Exception e) {
                eventSink.accept(ContainerEvent.error("Dropping Database",
                        "Failed to drop database '" + databaseName + "': " + e.getMessage()));
                return;
            }
        }

        eventSink.accept(ContainerEvent.success("Complete",
                "Container " + containerId + " removed successfully."));
    }
}
