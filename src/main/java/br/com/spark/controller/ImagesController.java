package br.com.spark.controller;

import br.com.spark.model.DockerImage;
import br.com.spark.model.Response;
import br.com.spark.util.CommandHandler;
import br.com.spark.util.ImageMapper;
import br.com.spark.util.ResponseWrapper;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

@Path("/images")
public class ImagesController extends CommandHandler {


    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerImage> getImages() {
        List<DockerImage> images = new ArrayList<>();

        List<String> responseDockerImageList;

        responseDockerImageList = executeCommand("docker images");

        if (responseDockerImageList != null &&
                !responseDockerImageList.isEmpty()) {

            ImageMapper mapper = new ImageMapper();
            images = mapper.mapDockerImage(responseDockerImageList);

            return images;
        }
        return images;
    }


    @POST
    @Path("/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeDockerImage(DockerImage image) {
        List<String> response;

        response = executeCommand("docker rmi " + image.getImageId());

        return ResponseWrapper.wrapper(response);
    }

}
