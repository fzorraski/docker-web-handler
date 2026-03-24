package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.model.HostMemoryStatus;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class MemoryGuardService {

    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");

    @ConfigProperty(name = "container.memory-guard.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "container.memory-guard.threshold-mb", defaultValue = "2048")
    long thresholdMb;

    private boolean supported;

    @PostConstruct
    void init() {
        supported = Files.isReadable(PROC_MEMINFO);
        if (enabled && !supported) {
            Log.warn("Memory guard enabled but /proc/meminfo is not readable. Guard will be disabled.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if there is enough memory for a container requesting a specific amount.
     * Returns null if OK, or an error message if not.
     */
    public String checkMemoryFor(Long requestedMb) {
        if (!enabled || !supported) return null;
        long availableMb = readAvailableMb();
        if (availableMb < 0) return null; // couldn't read, don't block
        if (requestedMb != null && requestedMb > 0 && requestedMb > availableMb) {
            return "Requested memory (" + requestedMb + " MB) exceeds available host memory (" + availableMb + " MB).";
        }
        if (availableMb < thresholdMb) {
            return "Insufficient host memory. Available: " + availableMb + " MB, required: " + thresholdMb + " MB.";
        }
        return null;
    }

    public HostMemoryStatus getStatus() {
        if (!supported) {
            return new HostMemoryStatus(false, enabled, true, 0, 0, 0, thresholdMb);
        }
        long totalMb = readFieldMb("MemTotal");
        long availableMb = readAvailableMb();
        if (totalMb < 0 || availableMb < 0) {
            return new HostMemoryStatus(false, enabled, true, 0, 0, 0, thresholdMb);
        }
        long usedMb = totalMb - availableMb;
        boolean available = !enabled || availableMb >= thresholdMb;
        return new HostMemoryStatus(true, enabled, available, totalMb, usedMb, availableMb, thresholdMb);
    }

    private long readAvailableMb() {
        return readFieldMb("MemAvailable");
    }

    private long readFieldMb(String field) {
        try {
            for (String line : Files.readAllLines(PROC_MEMINFO)) {
                if (line.startsWith(field + ":")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) / 1024; // kB to MB
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            Log.warnf("Failed to read %s from /proc/meminfo: %s", field, e.getMessage());
        }
        return -1;
    }
}
