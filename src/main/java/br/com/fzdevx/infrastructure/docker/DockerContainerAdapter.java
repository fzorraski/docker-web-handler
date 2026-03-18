package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.PullImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    public void streamLogs(String containerId, int tail, Consumer<ContainerEvent> eventSink, Supplier<Boolean> isActive) {
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (!isActive.get()) {
                    try { close(); } catch (IOException ignored) {}
                    return;
                }
                String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    String streamType = frame.getStreamType() == StreamType.STDERR ? "STDERR" : "STDOUT";
                    eventSink.accept(ContainerEvent.info(streamType, line));
                }
            }
        };

        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTail(tail)
                    .withTimestamps(true)
                    .exec(callback);

            callback.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error("Logs", "Log stream error: " + e.getMessage()));
        }
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
