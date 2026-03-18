package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ContainerEvent;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// ⚠ SOLID — DIP: port interface abstracting Docker container operations for use cases
public interface DockerContainerPort {

    List<Container> listContainers(boolean showAll);

    void stopContainer(String containerId);

    void removeContainer(String containerId);

    void startContainer(String containerId);

    CreateContainerCmd createContainerCmd(String imageRef);

    void pullImage(String imageRef, String repository, String tag, Consumer<ContainerEvent> eventSink) throws InterruptedException;

    void streamLogs(String containerId, int tail, Consumer<ContainerEvent> eventSink, Supplier<Boolean> isActive);
}
