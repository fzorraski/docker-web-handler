package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.usecase.ListImagesUseCase;
import br.com.fzdevx.domain.model.DockerImage;
import br.com.fzdevx.interfaces.rest.dto.Response;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveImageCmd;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImagesControllerTest {

    @Mock DockerClient dockerClient;
    @Mock ListImagesUseCase listImagesUseCase;

    @InjectMocks
    ImagesController controller;

    // ---- getImages ----

    @Test
    void getImages_delegatesToUseCase() {
        DockerImage img = new DockerImage();
        img.setRepository("postgres");
        img.setTag("16");
        when(listImagesUseCase.execute()).thenReturn(List.of(img));

        List<DockerImage> result = controller.getImages();

        assertEquals(1, result.size());
        assertEquals("postgres", result.getFirst().getRepository());
        verify(listImagesUseCase).execute();
    }

    @Test
    void getImages_emptyList() {
        when(listImagesUseCase.execute()).thenReturn(Collections.emptyList());

        List<DockerImage> result = controller.getImages();

        assertTrue(result.isEmpty());
    }

    // ---- removeDockerImage: validation ----

    @Test
    void removeDockerImage_nullImageId_returnsErrorState() {
        DockerImage req = new DockerImage();
        req.setImageId(null);

        Response response = controller.removeDockerImage(req);

        assertEquals(0, response.getState());
        assertNotNull(response.getMessage());
    }

    @Test
    void removeDockerImage_blankImageId_returnsErrorState() {
        DockerImage req = new DockerImage();
        req.setImageId("   ");

        Response response = controller.removeDockerImage(req);

        assertEquals(0, response.getState());
    }

    @Test
    void removeDockerImage_invalidImageId_returnsErrorState() {
        DockerImage req = new DockerImage();
        req.setImageId("NOT_VALID!");

        Response response = controller.removeDockerImage(req);

        assertEquals(0, response.getState());
        verifyNoInteractions(dockerClient);
    }

    // ---- removeDockerImage: success ----

    @Test
    void removeDockerImage_validId_removesAndReturnsSuccess() {
        RemoveImageCmd cmd = mock(RemoveImageCmd.class);
        when(dockerClient.removeImageCmd("abcdef1234")).thenReturn(cmd);

        DockerImage req = new DockerImage();
        req.setImageId("abcdef1234");

        Response response = controller.removeDockerImage(req);

        assertEquals(1, response.getState());
        verify(cmd).exec();
    }

    @Test
    void removeDockerImage_sha256Id_removesAndReturnsSuccess() {
        RemoveImageCmd cmd = mock(RemoveImageCmd.class);
        when(dockerClient.removeImageCmd("sha256:abcdef1234")).thenReturn(cmd);

        DockerImage req = new DockerImage();
        req.setImageId("sha256:abcdef1234");

        Response response = controller.removeDockerImage(req);

        assertEquals(1, response.getState());
    }

    // ---- removeDockerImage: image in use ----

    @Test
    void removeDockerImage_imageInUse_returnsState100() {
        RemoveImageCmd cmd = mock(RemoveImageCmd.class);
        when(dockerClient.removeImageCmd("abcdef1234")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new RuntimeException("image is being used by container xyz"));

        DockerImage req = new DockerImage();
        req.setImageId("abcdef1234");

        Response response = controller.removeDockerImage(req);

        assertEquals(100, response.getState());
        assertTrue(response.getMessage().contains("image is being used"));
    }

    // ---- removeDockerImage: image has children ----

    @Test
    void removeDockerImage_imageHasChildren_returnsState101() {
        RemoveImageCmd cmd = mock(RemoveImageCmd.class);
        when(dockerClient.removeImageCmd("abcdef1234")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new RuntimeException("image has dependent child images"));

        DockerImage req = new DockerImage();
        req.setImageId("abcdef1234");

        Response response = controller.removeDockerImage(req);

        assertEquals(101, response.getState());
    }

    // ---- removeDockerImage: not found ----

    @Test
    void removeDockerImage_notFound_returnsSuccessState() {
        RemoveImageCmd cmd = mock(RemoveImageCmd.class);
        when(dockerClient.removeImageCmd("abcdef1234")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new NotFoundException("No such image"));

        DockerImage req = new DockerImage();
        req.setImageId("abcdef1234");

        Response response = controller.removeDockerImage(req);

        // NotFoundException goes to catch(NotFoundException) branch, sets SUCCESS state via wrapper
        assertEquals(1, response.getState());
    }
}
