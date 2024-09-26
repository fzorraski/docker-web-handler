package br.com.spark.controller;

import br.com.spark.model.DockerImage;
import br.com.spark.model.Response;
import br.com.spark.util.BytesConverter;
import br.com.spark.util.Constants;
import br.com.spark.util.DateFormatter;
import br.com.spark.util.ResponseWrapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;


@Path("/images")
public class ImagesController {

    @Inject
    DockerClient dockerClient;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerImage> getImages() {
        List<Image> dockerImages = dockerClient.listImagesCmd().exec();
        List<DockerImage> images = new ArrayList<>();

        for (Image img : dockerImages) {
            if (img.getRepoTags().length > 0 && img.getRepoTags()[0].split(":")[0].equals(Constants.DOCKER_WEB_HANDLER_IMAGE))
                continue;

            DockerImage dockerImage = new DockerImage();
            dockerImage.setRepository(img.getRepoTags().length > 0 ? img.getRepoTags()[0].split(":")[0] : "-");
            dockerImage.setImageId(img.getId().substring(0, 20));
            dockerImage.setTag(img.getRepoTags().length > 0 ? img.getRepoTags()[0].split(":")[1] : "-");
            dockerImage.setCreated(DateFormatter.convertSecondsToDate(img.getCreated()));
            dockerImage.setSize(BytesConverter.bytesToMegabytesFormatted(img.getSize(), 2));

            images.add(dockerImage);
        }

        return images;
    }

    @POST
    @Path("/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeDockerImage(DockerImage image) {
        List<String> commandResponse = new ArrayList<>();
        try {
            dockerClient.removeImageCmd(image.getImageId()).exec();
            commandResponse.add("Image removed successfully.");
        } catch (NotFoundException e) {
            String errorMessage = e.getMessage();
            commandResponse.add("Image not found: " + errorMessage);
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            commandResponse.add(errorMessage);
        }
        return ResponseWrapper.wrapper(commandResponse);
    }


}
