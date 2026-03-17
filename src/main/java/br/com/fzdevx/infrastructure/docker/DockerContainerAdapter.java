package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.PullImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

// ⚠ SOLID — DIP: adapter implementing DockerContainerPort for use cases
@ApplicationScoped
public class DockerContainerAdapter implements DockerContainerPort {

    @Inject
    DockerClient dockerClient;

    @Override
    public List<Container> listContainers(boolean showAll) {
        return dockerClient.listContainersCmd().withShowAll(showAll).exec();
    }

    @Override
    public void stopContainer(String containerId) {
        dockerClient.stopContainerCmd(containerId).exec();
    }

    @Override
    public void removeContainer(String containerId) {
        dockerClient.removeContainerCmd(containerId).exec();
    }

    @Override
    public void startContainer(String containerId) {
        dockerClient.startContainerCmd(containerId).exec();
    }

    @Override
    public CreateContainerCmd createContainerCmd(String imageRef) {
        return dockerClient.createContainerCmd(imageRef);
    }

    @Override
    public void pullImage(String imageRef, String repository, String tag,
                          Consumer<ContainerEvent> eventSink) throws InterruptedException {
        AtomicLong lastProgressSent = new AtomicLong(0);

        dockerClient.pullImageCmd(imageRef)
                .exec(new PullImageResultCallback() {
                    @Override
                    public void onNext(PullResponseItem item) {
                        super.onNext(item);
                        if (item.getStatus() == null) return;

                        long now = System.currentTimeMillis();
                        boolean isCompletionEvent = item.getStatus().contains("complete")
                                || item.getStatus().contains("Downloaded")
                                || item.getStatus().contains("Already exists");

                        if (isCompletionEvent || now - lastProgressSent.get() > 500) {
                            lastProgressSent.set(now);
                            String msg = item.getId() != null
                                    ? item.getId() + ": " + item.getStatus()
                                    : item.getStatus();
                            eventSink.accept(ContainerEvent.progress("Pulling", msg, -1));
                        }
                    }
                }).awaitCompletion();
    }
}
