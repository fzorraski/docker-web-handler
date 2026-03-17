package br.com.fzdevx.application.port;

import com.github.dockerjava.api.model.Image;

import java.util.List;

// ⚠ SOLID — DIP: port interface abstracting Docker image operations for use cases
public interface DockerImagePort {

    List<Image> listImages();

    void removeImage(String imageId);
}
