package br.com.fzdevx.application.port;

import com.github.dockerjava.api.command.PruneCmd;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.PruneResponse;

import java.util.List;

public interface DockerImagePort {

    List<Image> listImages();

    void removeImage(String imageId);

    PruneResponse pruneImages(boolean all);
}
