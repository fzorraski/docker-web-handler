package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerImagePort;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.PruneResponse;
import com.github.dockerjava.api.model.PruneType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class DockerImageAdapter implements DockerImagePort {

    @Inject
    DockerClient dockerClient;

    @Override
    public List<Image> listImages() {
        return dockerClient.listImagesCmd().exec();
    }

    @Override
    public void removeImage(String imageId) {
        dockerClient.removeImageCmd(imageId).exec();
    }

    @Override
    public PruneResponse pruneImages(boolean all) {
        return dockerClient.pruneCmd(PruneType.IMAGES)
                .withDangling(!all)
                .exec();
    }
}
