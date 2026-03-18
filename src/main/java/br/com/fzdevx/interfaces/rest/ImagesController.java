package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DockerImage;
import br.com.fzdevx.interfaces.rest.dto.Response;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.interfaces.rest.dto.ResponseWrapper;
import br.com.fzdevx.application.usecase.ListImagesUseCase;
import com.github.dockerjava.api.DockerClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@Path("/images")
public class ImagesController {

    @Inject
    DockerClient dockerClient;

    @Inject
    ListImagesUseCase listImagesUseCase;

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DockerImage> getImages() {
        return listImagesUseCase.execute();
    }

    @POST
    @Path("/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeDockerImage(DockerImage image) {
        Optional<String> idError = InputValidator.validateImageId(image.getImageId());
        if (idError.isPresent()) {
            Response response = new Response();
            response.setState(0);
            response.setMessage(idError.get());
            return response;
        }

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
