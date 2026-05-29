package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.logging.Log;
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
import java.util.Map;
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
    @ConfigProperty(name = "container.port-allocation.respect-stopped", defaultValue = "false")
    boolean respectStoppedPorts;

    @Inject
    Config config;

    /**
     * Returns the list of container ports configured for the given repository.
     * Empty list if port mapping is disabled or not configured.
     */
    public List<Integer> getContainerPorts(String repository) {
        boolean effectiveEnabled = config.getOptionalValue(
                "repository.port-mapping.enabled." + repository, Boolean.class)
                .orElse(portMappingEnabled);
        if (!effectiveEnabled) {
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
     * Returns the effective host port start for the given repository.
     */
    public int getHostPortStart(String repository) {
        return config.getOptionalValue(
                "repository.port-mapping.host-port-start." + repository, Integer.class)
                .orElse(hostPortStart);
    }

    /**
     * Finds the requested number of available host ports, starting from the
     * configured threshold. Checks both Docker-mapped ports and OS-level
     * socket availability.
     *
     * @param count number of ports needed
     * @return ordered list of available host ports
     */
    public List<Integer> findAvailablePorts(int count) {
        return findAvailablePorts(count, hostPortStart);
    }

    public List<Integer> findAvailablePorts(int count, int startPort) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        // Docker API calls happen OUTSIDE the reservation lock so concurrent
        // allocations don't serialize on slow inspect round-trips.
        Set<Integer> dockerPorts = collectDockerHostPorts();
        return reserveScannedPorts(dockerPorts, count, startPort);
    }

    /**
     * Finds available host ports, trying the preferred ports first.
     * Preferred ports that are free and not reserved are used directly.
     * If any preferred port is unavailable, a new port is found via scan.
     *
     * @param preferred list of host ports to try first (e.g., from old container)
     * @param count     total number of ports needed
     * @return ordered list of available host ports
     */
    public List<Integer> findAvailablePortsPreferring(List<Integer> preferred, int count) {
        return findAvailablePortsPreferring(preferred, count, hostPortStart);
    }

    public List<Integer> findAvailablePortsPreferring(List<Integer> preferred, int count, int startPort) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        Set<Integer> dockerPorts = collectDockerHostPorts();
        return reservePreferredOrScanned(dockerPorts, preferred, count, startPort);
    }

    private synchronized List<Integer> reserveScannedPorts(Set<Integer> dockerPorts, int count, int startPort) {
        List<Integer> available = new ArrayList<>(count);
        int candidate = startPort;
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
                    "Not enough available host ports starting from " + startPort
                            + ". Needed " + count + ", found " + available.size() + ".");
        }
        reservedPorts.addAll(available);
        return available;
    }

    private synchronized List<Integer> reservePreferredOrScanned(Set<Integer> dockerPorts,
            List<Integer> preferred, int count, int startPort) {
        List<Integer> result = new ArrayList<>(count);
        Set<Integer> used = new HashSet<>();

        // Try preferred ports first — these come from a just-removed container,
        // so skip OS-level socket check (may report false negatives for recently freed ports)
        for (int port : preferred) {
            if (result.size() >= count) break;
            if (!dockerPorts.contains(port) && !reservedPorts.contains(port)) {
                result.add(port);
                used.add(port);
            }
        }

        // Fill remaining with scanned ports
        if (result.size() < count) {
            int candidate = startPort;
            while (result.size() < count && candidate <= MAX_PORT) {
                if (!dockerPorts.contains(candidate)
                        && !reservedPorts.contains(candidate)
                        && !used.contains(candidate)
                        && isPortFree(candidate)) {
                    result.add(candidate);
                }
                candidate++;
            }
        }

        if (result.size() < count) {
            throw new RuntimeException(
                    "Not enough available host ports. Needed " + count + ", found " + result.size() + ".");
        }

        reservedPorts.addAll(result);
        return result;
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
            if (container.getPorts() != null) {
                for (ContainerPort port : container.getPorts()) {
                    if (port.getPublicPort() != null) {
                        usedPorts.add(port.getPublicPort());
                    }
                }
            }
            if (respectStoppedPorts && isStableStopped(container.getState())) {
                usedPorts.addAll(inspectConfiguredHostPorts(container.getId()));
            }
        }
        return usedPorts;
    }

    /**
     * Only "exited" and "dead" containers are stable enough to reserve their bindings:
     *  - "running" / "paused": already covered by the listing's public-port view.
     *  - "restarting": transient — will be running shortly, no need to inspect.
     *  - "created": never bound to host ports — reserving them would exhaust the
     *    pool with phantom reservations for one-shot artifacts that may never run.
     *  - null state: treat as unknown and skip; better to risk a re-bind collision
     *    than to pay an inspect for every state-null container.
     */
    private static boolean isStableStopped(String state) {
        return "exited".equalsIgnoreCase(state) || "dead".equalsIgnoreCase(state);
    }

    private Set<Integer> inspectConfiguredHostPorts(String containerId) {
        Set<Integer> ports = new HashSet<>();
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getHostConfig() == null) {
                return ports;
            }
            Ports bindings = inspect.getHostConfig().getPortBindings();
            if (bindings == null || bindings.getBindings() == null) {
                return ports;
            }
            for (Map.Entry<ExposedPort, Ports.Binding[]> entry : bindings.getBindings().entrySet()) {
                if (entry.getValue() == null) continue;
                for (Ports.Binding binding : entry.getValue()) {
                    addParsedHostPorts(binding.getHostPortSpec(), ports);
                }
            }
        } catch (NotFoundException e) {
            // Container was removed between list and inspect — benign race; do nothing.
            Log.debugf("Container '%s' removed before port binding inspection.", containerId);
        } catch (Exception e) {
            Log.warnf("Failed to inspect stopped container '%s' for port bindings: %s",
                    containerId, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return ports;
    }

    /**
     * Parses a Docker HostPortSpec which may be a single port ("8080") or a
     * range ("8000-8010"), and adds every port it covers to {@code out}.
     * Out-of-range or malformed values are skipped silently — Docker itself
     * validates these at container-create time.
     */
    private static void addParsedHostPorts(String spec, Set<Integer> out) {
        if (spec == null) return;
        String trimmed = spec.trim();
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            // 16 chars is a very generous cap for "65535-65535"; anything longer
            // can't be a valid port spec and isn't worth parsing.
            return;
        }
        int dash = trimmed.indexOf('-');
        try {
            if (dash < 0) {
                int port = Integer.parseInt(trimmed);
                if (port >= 1 && port <= MAX_PORT) out.add(port);
            } else {
                int start = Integer.parseInt(trimmed.substring(0, dash).trim());
                int end = Integer.parseInt(trimmed.substring(dash + 1).trim());
                if (start < 1 || end < start || end > MAX_PORT) return;
                for (int p = start; p <= end; p++) out.add(p);
            }
        } catch (NumberFormatException ignored) {
            // Not a valid integer or range — Docker would reject this on create, skip.
        }
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
