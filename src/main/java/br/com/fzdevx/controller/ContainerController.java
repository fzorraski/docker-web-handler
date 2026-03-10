package br.com.fzdevx.controller;

import br.com.fzdevx.model.DockerContainer;
import br.com.fzdevx.model.RunContainerRequest;
import br.com.fzdevx.model.Response;
import br.com.fzdevx.service.RegistryService;
import br.com.fzdevx.util.Constants;
import br.com.fzdevx.util.DateFormatter;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Container;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


@Path("/containers")
public class ContainerController {


    @Inject
    DockerClient dockerClient;

    @Inject
    RegistryService registryService;

    @Inject
    @ConfigProperty(name = "allowed.run.repositories")
    Optional<String> allowedRunRepositories;

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
            containers.add(dockerContainer);
        }

        return containers;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/stop")
    public boolean stopContainer(DockerContainer dockerContainer) {
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
        try {
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

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/run")
    public Response runContainer(RunContainerRequest request) {
        Response response = new Response();

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
            if (request.getEnvVars() != null && !request.getEnvVars().isEmpty()) {
                createCmd.withEnv(request.getEnvVars());
            }

            CreateContainerResponse container = createCmd.exec();
            dockerClient.startContainerCmd(container.getId()).exec();

            response.setState(1);
            response.setMessage("Container started successfully from " + imageRef);
        } catch (Exception e) {
            response.setState(0);
            response.setMessage(e.getMessage());
        }

        return response;
    }

}
