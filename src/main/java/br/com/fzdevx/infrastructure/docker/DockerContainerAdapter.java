package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ContainerStats;
import br.com.fzdevx.infrastructure.registry.RegistryService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.PullImageResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;


@ApplicationScoped
public class DockerContainerAdapter implements DockerContainerPort {

    @Inject
    DockerClient dockerClient;

    @Inject
    RegistryService registryService;

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
        PullImageCmd pullCmd = dockerClient.pullImageCmd(imageRef);
        AuthConfig authConfig = registryService.buildAuthConfig(repository, tag);
        if (authConfig != null) {
            pullCmd.withAuthConfig(authConfig);
        }

        AtomicLong lastProgressSent = new AtomicLong(0);

        pullCmd.exec(new PullImageResultCallback() {
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

    @Override
    public void streamStats(String containerId, Consumer<ContainerStats> statsSink, Supplier<Boolean> isActive) {
        final long[] prevNetRx = {0};
        final long[] prevNetTx = {0};
        final long[] prevTimestamp = {0};

        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics stats) {
                if (!isActive.get()) {
                    try { close(); } catch (IOException ignored) {}
                    return;
                }
                statsSink.accept(mapStats(stats, prevNetRx, prevNetTx, prevTimestamp));
            }
        };

        try {
            dockerClient.statsCmd(containerId).exec(callback);
            callback.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
    }

    private ContainerStats mapStats(Statistics stats, long[] prevNetRx, long[] prevNetTx, long[] prevTs) {
        ContainerStats cs = new ContainerStats();
        cs.setTimestamp(stats.getRead());

        // CPU percentage
        if (stats.getCpuStats() != null && stats.getPreCpuStats() != null
                && stats.getCpuStats().getCpuUsage() != null
                && stats.getPreCpuStats().getCpuUsage() != null
                && stats.getCpuStats().getSystemCpuUsage() != null
                && stats.getPreCpuStats().getSystemCpuUsage() != null) {
            long cpuDelta = stats.getCpuStats().getCpuUsage().getTotalUsage()
                    - stats.getPreCpuStats().getCpuUsage().getTotalUsage();
            long sysDelta = stats.getCpuStats().getSystemCpuUsage()
                    - stats.getPreCpuStats().getSystemCpuUsage();
            long onlineCpus = stats.getCpuStats().getOnlineCpus() != null
                    ? stats.getCpuStats().getOnlineCpus() : 1;
            if (sysDelta > 0 && cpuDelta >= 0) {
                cs.setCpuPercent(((double) cpuDelta / sysDelta) * onlineCpus * 100.0);
            }
        }

        // Memory
        if (stats.getMemoryStats() != null) {
            long usage = stats.getMemoryStats().getUsage() != null ? stats.getMemoryStats().getUsage() : 0;
            long cache = 0;
            if (stats.getMemoryStats().getStats() != null && stats.getMemoryStats().getStats().getCache() != null) {
                cache = stats.getMemoryStats().getStats().getCache();
            }
            long memUsed = Math.max(0, usage - cache);
            long limit = stats.getMemoryStats().getLimit() != null ? stats.getMemoryStats().getLimit() : 0;
            cs.setMemoryUsage(memUsed);
            cs.setMemoryLimit(limit);
            cs.setMemoryPercent(limit > 0 ? ((double) memUsed / limit) * 100.0 : 0);
        }

        // Network I/O
        long rxTotal = 0, txTotal = 0;
        Map<String, StatisticNetworksConfig> networks = stats.getNetworks();
        if (networks != null) {
            for (StatisticNetworksConfig net : networks.values()) {
                rxTotal += net.getRxBytes();
                txTotal += net.getTxBytes();
            }
        }
        cs.setNetworkRxBytes(rxTotal);
        cs.setNetworkTxBytes(txTotal);

        long now = System.currentTimeMillis();
        if (prevTs[0] > 0) {
            double elapsed = (now - prevTs[0]) / 1000.0;
            if (elapsed > 0) {
                cs.setNetworkRxRate((long) ((rxTotal - prevNetRx[0]) / elapsed));
                cs.setNetworkTxRate((long) ((txTotal - prevNetTx[0]) / elapsed));
            }
        }
        prevNetRx[0] = rxTotal;
        prevNetTx[0] = txTotal;
        prevTs[0] = now;

        // Block I/O
        if (stats.getBlkioStats() != null && stats.getBlkioStats().getIoServiceBytesRecursive() != null) {
            for (BlkioStatEntry entry : stats.getBlkioStats().getIoServiceBytesRecursive()) {
                if ("read".equalsIgnoreCase(entry.getOp())) {
                    cs.setBlockReadBytes(cs.getBlockReadBytes() + entry.getValue());
                } else if ("write".equalsIgnoreCase(entry.getOp())) {
                    cs.setBlockWriteBytes(cs.getBlockWriteBytes() + entry.getValue());
                }
            }
        }

        // PIDs
        if (stats.getPidsStats() != null && stats.getPidsStats().getCurrent() != null) {
            cs.setPids(stats.getPidsStats().getCurrent());
        }

        return cs;
    }
}
