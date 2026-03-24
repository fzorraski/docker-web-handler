package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Detects the container this application is running in.
 * Tries multiple strategies: HOSTNAME vs container ID, HOSTNAME vs container name,
 * and /proc/1/cpuset (cgroup v1) as fallback.
 */
public final class SelfContainerDetector {

    private SelfContainerDetector() {}

    private static volatile String cachedSelfId;

    public static String findSelfContainerId(List<Container> allContainers) {
        if (cachedSelfId != null) return cachedSelfId;

        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) return null;

        for (Container c : allContainers) {
            // Match by container ID prefix (default Docker HOSTNAME = short container ID)
            if (c.getId().startsWith(hostname)) {
                cachedSelfId = c.getId();
                Log.debugf("Self-detected by container ID: %s", cachedSelfId);
                return cachedSelfId;
            }
            // Match by container name (docker-compose may set HOSTNAME = container_name)
            if (c.getNames() != null) {
                for (String name : c.getNames()) {
                    if (name.replaceFirst("/", "").equals(hostname)) {
                        cachedSelfId = c.getId();
                        Log.debugf("Self-detected by container name: %s -> %s", hostname, cachedSelfId);
                        return cachedSelfId;
                    }
                }
            }
        }

        // Fallback: read container ID from cgroup (works in cgroup v1)
        try {
            String cpuset = Files.readString(Path.of("/proc/1/cpuset")).trim();
            if (cpuset.length() > 1) {
                String cgroupId = cpuset.substring(cpuset.lastIndexOf('/') + 1);
                if (cgroupId.length() >= 12) {
                    for (Container c : allContainers) {
                        if (c.getId().startsWith(cgroupId)) {
                            cachedSelfId = c.getId();
                            Log.debugf("Self-detected by cpuset: %s", cachedSelfId);
                            return cachedSelfId;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static boolean isSelf(Container c, List<Container> allContainers) {
        String selfId = findSelfContainerId(allContainers);
        return selfId != null && c.getId().equals(selfId);
    }

    public static String findSelfImageId(List<Container> allContainers) {
        String selfId = findSelfContainerId(allContainers);
        if (selfId == null) return null;
        for (Container c : allContainers) {
            if (c.getId().equals(selfId)) return c.getImageId();
        }
        return null;
    }
}
