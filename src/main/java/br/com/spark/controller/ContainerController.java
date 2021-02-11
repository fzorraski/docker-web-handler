package br.com.spark.controller;

import br.com.spark.util.CommandHandler;
import br.com.spark.util.ContainerMapper;
import br.com.spark.model.Container;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

@Path("/containers")
public class ContainerController extends CommandHandler {

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Container> getContainers() {
        List<String> searchContainerResultList;
        List<Container> containers = new ArrayList<>();

        searchContainerResultList = executeCommand("docker ps -a");

        if (searchContainerResultList != null &&
                !searchContainerResultList.isEmpty()) {

            ContainerMapper mapper = new ContainerMapper();
            containers = mapper.map(searchContainerResultList);

            return containers;
        }

        return containers;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/stop")
    public boolean stopContainer(Container container) {
        List<String> result;
        result = executeCommand("docker stop " + container.getContainerId());

        if (result.size() > 0) {
            return result.get(0).equals(container.getContainerId());
        }
        
        return false;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/remove")
    public boolean removeContainer(Container container) {
        List<String> result;

        stopContainer(container);
        result = executeCommand("docker rm " + container.getContainerId());

        if (result.size() > 0) {
            return result.get(0).equals(container.getContainerId());
        }

        return false;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/start")
    public boolean startContainer(Container container) {
        List<String> result;

        result = executeCommand("docker start " + container.getContainerId());

        if (result.size() > 0) {
            return result.get(0).equals(container.getContainerId());
        }

        return false;
    }

}
