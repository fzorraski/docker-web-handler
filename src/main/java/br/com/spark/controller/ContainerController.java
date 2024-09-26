package br.com.spark.controller;

import br.com.spark.model.DockerContainer;
import br.com.spark.util.Constants;
import br.com.spark.util.DateFormatter;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Path("/containers")
public class ContainerController {


    @Inject
    DockerClient dockerClient;

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

}
