package br.com.fzdevx.usecase;

import br.com.fzdevx.model.ContainerEvent;
import br.com.fzdevx.model.RunContainerRequest;
import br.com.fzdevx.service.ContainerExpirationService;
import br.com.fzdevx.service.RegistryService;
import br.com.fzdevx.util.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.PullImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@ApplicationScoped
public class RunContainerUseCase {

    @Inject
    DockerClient dockerClient;

    @Inject
    RegistryService registryService;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

    public void execute(RunContainerRequest request, Consumer<ContainerEvent> eventSink) {
        // Step 1: Validate inputs
        eventSink.accept(ContainerEvent.info("Validating", "Validating input parameters..."));

        Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
        if (repoError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", repoError.get()));
            return;
        }

        Optional<String> tagError = InputValidator.validateTag(request.getTag());
        if (tagError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", tagError.get()));
            return;
        }

        Optional<String> nameError = InputValidator.validateContainerName(request.getContainerName());
        if (nameError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", nameError.get()));
            return;
        }

        Optional<String> envError = InputValidator.validateEnvVars(request.getEnvVars());
        if (envError.isPresent()) {
            eventSink.accept(ContainerEvent.error("Validating", envError.get()));
            return;
        }

        // Step 2: Check whitelist
        eventSink.accept(ContainerEvent.info("Validating", "Checking repository permissions..."));

        List<String> allowed = allowedRunRepositories
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList())
                .orElse(Collections.emptyList());

        if (allowed.isEmpty()) {
            eventSink.accept(ContainerEvent.error("Validating",
                    "No repositories are allowed to run. Configure ALLOWED_RUN_REPOSITORIES."));
            return;
        }

        if (!allowed.contains(request.getRepository())) {
            eventSink.accept(ContainerEvent.error("Validating",
                    "Repository '" + request.getRepository() + "' is not in the allowed list."));
            return;
        }

        String imageRef = registryService.buildFullImageRef(request.getRepository(), request.getTag());
        eventSink.accept(ContainerEvent.info("Validating", "All validations passed."));

        // Step 2: Pull image
        eventSink.accept(ContainerEvent.info("Pulling", "Pulling image " + imageRef + "..."));

        try {
            pullImage(imageRef, eventSink);
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Pulling", "Failed to pull image: " + e.getMessage()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Pulling", "Image pulled successfully."));

        // Step 3: Create container
        eventSink.accept(ContainerEvent.info("Creating", "Creating container..."));

        CreateContainerResponse container;
        try {
            container = createContainer(imageRef, request);
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Creating", "Failed to create container: " + e.getMessage()));
            return;
        }

        eventSink.accept(ContainerEvent.info("Creating", "Container created."));

        // Step 4: Start container
        eventSink.accept(ContainerEvent.info("Starting", "Starting container..."));

        try {
            dockerClient.startContainerCmd(container.getId()).exec();
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Starting", "Failed to start container: " + e.getMessage()));
            return;
        }

        // Step 5: Schedule expiration if configured
        String expirationMessage = scheduleExpiration(request, container.getId());

        eventSink.accept(ContainerEvent.success("Complete",
                "Container started successfully from " + imageRef + expirationMessage));
    }

    private void pullImage(String imageRef, Consumer<ContainerEvent> eventSink) throws InterruptedException {
        PullImageCmd pullCmd = dockerClient.pullImageCmd(imageRef);
        AuthConfig authConfig = registryService.buildAuthConfig();
        if (authConfig != null) {
            pullCmd.withAuthConfig(authConfig);
        }

        AtomicLong lastProgressSent = new AtomicLong(0);

        pullCmd.exec(new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                super.onNext(item);
                if (item.getStatus() == null) return;

                long now = System.currentTimeMillis();
                boolean isCompletionEvent = item.getStatus().contains("complete")
                        || item.getStatus().contains("Downloaded")
                        || item.getStatus().contains("Already exists");

                if (isCompletionEvent || now - lastProgressSent.get() > 500) {
                    lastProgressSent.set(now);
                    String msg = item.getId() != null
                            ? item.getId() + ": " + item.getStatus()
                            : item.getStatus();
                    eventSink.accept(ContainerEvent.progress("Pulling", msg, -1));
                }
            }
        }).awaitCompletion();
    }

    private CreateContainerResponse createContainer(String imageRef, RunContainerRequest request) {
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageRef);

        if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
            createCmd.withName(request.getContainerName().trim());
        }
        if (request.getEnvVars() != null && !request.getEnvVars().isEmpty()) {
            createCmd.withEnv(request.getEnvVars());
        }

        return createCmd.exec();
    }

    private String scheduleExpiration(RunContainerRequest request, String fullContainerId) {
        if (request.getExpiresAt() == null || request.getExpiresAt().isBlank()) {
            return "";
        }

        LocalDateTime ldt = LocalDateTime.parse(request.getExpiresAt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Instant expiresInstant = ldt.atZone(ZoneId.systemDefault()).toInstant();
        String shortId = fullContainerId.substring(0, 10);
        expirationService.schedule(shortId, fullContainerId, expiresInstant);

        return " (expires at " + ldt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ")";
    }
}
