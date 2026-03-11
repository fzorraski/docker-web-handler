package br.com.fzdevx.controller;

import br.com.fzdevx.model.DockerContainer;
import br.com.fzdevx.model.RunContainerRequest;
import br.com.fzdevx.model.Response;
import br.com.fzdevx.service.ContainerExpirationService;
import br.com.fzdevx.service.RegistryService;
import br.com.fzdevx.util.Constants;
import br.com.fzdevx.util.DateFormatter;
import br.com.fzdevx.util.InputValidator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;


@Path("/containers")
public class ContainerController {


    @Inject
    DockerClient dockerClient;

    @Inject
    RegistryService registryService;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

    @Inject
    @ConfigProperty(name = "container.default-expiration-minutes", defaultValue = "480")
    int defaultExpirationMinutes;

    @Inject
    @ConfigProperty(name = "container.memory-limit.enabled", defaultValue = "false")
    boolean memoryLimitEnabled;

    @Inject
    Config config;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerContainer> getContainers() {
        List<Container> dockerContainers = dockerClient.listContainersCmd().withShowAll(true).exec();
        List<DockerContainer> containers = new ArrayList<>();

        for (Container dc : dockerContainers) {
            if (dc.getImage().equals(Constants.DOCKER_WEB_HANDLER_IMAGE)) continue;

            DockerContainer dockerContainer = new DockerContainer();
            dockerContainer.setContainerId(dc.getId().substring(0, 10));
            dockerContainer.setCommand(dc.getCommand().length() > 15 ? dc.getCommand().substring(0, 15) : dc.getCommand());
            dockerContainer.setCreated(DateFormatter.convertSecondsToDate(dc.getCreated()));
            dockerContainer.setImage(dc.getImage().length() > 25 ? dc.getImage().substring(0, 25) : dc.getImage());
            dockerContainer.setNames(dc.getNames()[0].replaceFirst("/", ""));
            dockerContainer.setStatus(dc.getStatus());
            dockerContainer.setPorts(dc.getPorts().length > 0 ? Arrays.toString(dc.getPorts()) : "-");

            Instant expiresAt = expirationService.getExpiresAt(dockerContainer.getContainerId());
            if (expiresAt != null) {
                dockerContainer.setExpiresAt(expiresAt.toString());
            }

            containers.add(dockerContainer);
        }

        return containers;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/stop")
    public boolean stopContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        try {
            dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/remove")
    public boolean removeContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        try {
            expirationService.cancel(dockerContainer.getContainerId());
            try {
                dockerClient.stopContainerCmd(dockerContainer.getContainerId()).exec();
            } catch (Exception ignored) {
            }

            dockerClient.removeContainerCmd(dockerContainer.getContainerId()).exec();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/start")
    public boolean startContainer(DockerContainer dockerContainer) {
        if (InputValidator.validateContainerId(dockerContainer.getContainerId()).isPresent()) {
            return false;
        }
        try {
            dockerClient.startContainerCmd(dockerContainer.getContainerId()).exec();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @GET
    @Path("/allowed-repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getAllowedRepositories() {
        if (allowedRunRepositories.isEmpty() || allowedRunRepositories.get().isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(allowedRunRepositories.get().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @GET
    @Path("/repository-tags")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryTags(@QueryParam("repository") String repository) {
        Response response = new Response();

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            response.setState(0);
            response.setMessage(repoError.get());
            return response;
        }

        List<String> allowed = getAllowedRepositories();
        if (!allowed.contains(repository)) {
            response.setState(0);
            response.setMessage("Repository '" + repository + "' is not in the allowed list.");
            return response;
        }

        try {
            List<String> tags = registryService.fetchTags(repository);
            response.setState(1);
            response.setTags(tags);
        } catch (Exception e) {
            response.setState(0);
            response.setMessage("Failed to fetch tags: " + e.getMessage());
        }

        return response;
    }

    @GET
    @Path("/repository-env-keys")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, String>> getRepositoryEnvKeys(@QueryParam("repository") String repository) {
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Collections.emptyList();
        }
        String configKey = "repository.env-keys." + repository;
        Optional<String> value = config.getOptionalValue(configKey, String.class);
        if (value.isEmpty() || value.get().isBlank()) {
            return Collections.emptyList();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (String entry : value.get().split(",")) {
            String trimmed = entry.trim();
            int eq = trimmed.indexOf('=');
            Map<String, String> pair = new LinkedHashMap<>();
            if (eq >= 0) {
                pair.put("key", trimmed.substring(0, eq));
                pair.put("value", trimmed.substring(eq + 1));
            } else {
                pair.put("key", trimmed);
                pair.put("value", "");
            }
            result.add(pair);
        }
        return result;
    }

    private List<String> mergeHiddenEnvVars(String repository, List<String> userEnvVars, Long memoryMb) {
        Map<String, String> envMap = new LinkedHashMap<>();

        if (userEnvVars != null) {
            for (String envVar : userEnvVars) {
                int eq = envVar.indexOf('=');
                if (eq > 0) {
                    envMap.put(envVar.substring(0, eq), envVar.substring(eq + 1));
                }
            }
        }

        String configKey = "repository.hidden-env." + repository;
        Optional<String> hiddenValue = config.getOptionalValue(configKey, String.class);
        if (hiddenValue.isPresent() && !hiddenValue.get().isBlank()) {
            for (String entry : hiddenValue.get().split(",")) {
                String trimmed = entry.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    envMap.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
                }
            }
        }

        if (memoryMb != null) {
            String javaOptsVar = config.getOptionalValue("repository.java-opts-var." + repository, String.class)
                    .orElse(null);
            if (javaOptsVar != null) {
                long xmx = (long) (memoryMb * 0.75);
                long xms = (long) (memoryMb * 0.25);
                envMap.put(javaOptsVar, "-Xmx" + xmx + "m -Xms" + xms + "m");
            }
        }

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> e : envMap.entrySet()) {
            result.add(e.getKey() + "=" + e.getValue());
        }
        return result;
    }

    @GET
    @Path("/default-expiration-minutes")
    @Produces(MediaType.APPLICATION_JSON)
    public int getDefaultExpirationMinutes() {
        return defaultExpirationMinutes;
    }

    @GET
    @Path("/memory-limit-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isMemoryLimitEnabled() {
        return memoryLimitEnabled;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/run")
    public Response runContainer(RunContainerRequest request) {
        Response response = new Response();

        Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
        if (repoError.isPresent()) {
            response.setState(0);
            response.setMessage(repoError.get());
            return response;
        }

        Optional<String> tagError = InputValidator.validateTag(request.getTag());
        if (tagError.isPresent()) {
            response.setState(0);
            response.setMessage(tagError.get());
            return response;
        }

        Optional<String> nameError = InputValidator.validateContainerName(request.getContainerName());
        if (nameError.isPresent()) {
            response.setState(0);
            response.setMessage(nameError.get());
            return response;
        }

        Optional<String> envError = InputValidator.validateEnvVars(request.getEnvVars());
        if (envError.isPresent()) {
            response.setState(0);
            response.setMessage(envError.get());
            return response;
        }

        Optional<String> memError = InputValidator.validateMemoryMb(request.getMemoryMb());
        if (memError.isPresent()) {
            response.setState(0);
            response.setMessage(memError.get());
            return response;
        }

        List<String> allowed = getAllowedRepositories();
        if (allowed.isEmpty()) {
            response.setState(0);
            response.setMessage("No repositories are allowed to run. Configure the ALLOWED_RUN_REPOSITORIES environment variable.");
            return response;
        }

        if (!allowed.contains(request.getRepository())) {
            response.setState(0);
            response.setMessage("Repository '" + request.getRepository() + "' is not in the allowed list.");
            return response;
        }

        String imageRef = registryService.buildFullImageRef(request.getRepository(), request.getTag());

        try {
            PullImageCmd pullCmd = dockerClient.pullImageCmd(imageRef);
            AuthConfig authConfig = registryService.buildAuthConfig();
            if (authConfig != null) {
                pullCmd.withAuthConfig(authConfig);
            }
            pullCmd.start().awaitCompletion();

            CreateContainerCmd createCmd = dockerClient.createContainerCmd(imageRef);
            if (request.getContainerName() != null && !request.getContainerName().isBlank()) {
                createCmd.withName(request.getContainerName().trim());
            }
            if (request.getMemoryMb() != null) {
                createCmd.withHostConfig(HostConfig.newHostConfig()
                        .withMemory(request.getMemoryMb() * 1024 * 1024));
            }
            List<String> mergedEnvVars = mergeHiddenEnvVars(request.getRepository(), request.getEnvVars(), request.getMemoryMb());
            if (!mergedEnvVars.isEmpty()) {
                createCmd.withEnv(mergedEnvVars);
            }

            CreateContainerResponse container = createCmd.exec();
            dockerClient.startContainerCmd(container.getId()).exec();

            response.setState(1);

            if (request.getExpiresAt() != null && !request.getExpiresAt().isBlank()) {
                LocalDateTime ldt = LocalDateTime.parse(request.getExpiresAt(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                Instant expiresInstant = ldt.atZone(ZoneId.systemDefault()).toInstant();
                String shortId = container.getId().substring(0, 10);
                expirationService.schedule(shortId, container.getId(), expiresInstant);
                response.setMessage("Container started successfully from " + imageRef
                        + " (expires at " + ldt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ")");
            } else {
                response.setMessage("Container started successfully from " + imageRef);
            }
        } catch (Exception e) {
            response.setState(0);
            response.setMessage(e.getMessage());
        }

        return response;
    }

}
