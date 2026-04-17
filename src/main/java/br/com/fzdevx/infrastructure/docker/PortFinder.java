package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PortFinder {

    private static final int MAX_PORT = 65535;
    private final Set<Integer> reservedPorts = ConcurrentHashMap.newKeySet();

    @Inject
    DockerClient dockerClient;

    @Inject
    @ConfigProperty(name = "container.port-mapping.enabled", defaultValue = "false")
    boolean portMappingEnabled;

    @Inject
    @ConfigProperty(name = "container.port-mapping.host-port-start", defaultValue = "10000")
    int hostPortStart;

    @Inject
    Config config;

    /**
     * Returns the list of container ports configured for the given repository.
     * Empty list if port mapping is disabled or not configured.
     */
    public List<Integer> getContainerPorts(String repository) {
        if (!portMappingEnabled) {
            return Collections.emptyList();
        }

        Optional<String> portsValue = config.getOptionalValue(
                "repository.container-ports." + repository, String.class);

        if (portsValue.isEmpty() || portsValue.get().isBlank()) {
            return Collections.emptyList();
        }

        List<Integer> ports = new ArrayList<>();
        for (String segment : portsValue.get().split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            int port = Integer.parseInt(trimmed);
            if (port < 1 || port > MAX_PORT) {
                throw new IllegalArgumentException("Container port out of range: " + port);
            }
            ports.add(port);
        }
        return ports;
    }

    /**
     * Finds the requested number of available host ports, starting from the
     * configured threshold. Checks both Docker-mapped ports and OS-level
     * socket availability.
     *
     * @param count number of ports needed
     * @return ordered list of available host ports
     */
    public synchronized List<Integer> findAvailablePorts(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        Set<Integer> dockerPorts = collectDockerHostPorts();
        List<Integer> available = new ArrayList<>(count);
        int candidate = hostPortStart;

        while (available.size() < count && candidate <= MAX_PORT) {
            if (!dockerPorts.contains(candidate)
                    && !reservedPorts.contains(candidate)
                    && isPortFree(candidate)) {
                available.add(candidate);
            }
            candidate++;
        }

        if (available.size() < count) {
            throw new RuntimeException(
                    "Not enough available host ports starting from " + hostPortStart
                            + ". Needed " + count + ", found " + available.size() + ".");
        }

        reservedPorts.addAll(available);
        return available;
    }

    /**
     * Releases previously reserved ports so they can be re-used by future
     * calls to {@link #findAvailablePorts(int)}.
     */
    public void releasePorts(List<Integer> ports) {
        if (ports != null) {
            reservedPorts.removeAll(ports);
        }
    }

    private Set<Integer> collectDockerHostPorts() {
        Set<Integer> usedPorts = new HashSet<>();
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        for (Container container : containers) {
            if (container.getPorts() == null) continue;
            for (ContainerPort port : container.getPorts()) {
                if (port.getPublicPort() != null) {
                    usedPorts.add(port.getPublicPort());
                }
            }
        }
        return usedPorts;
    }

    private boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
