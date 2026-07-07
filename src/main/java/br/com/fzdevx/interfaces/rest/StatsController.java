package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/stats")
@RequiresPermission(Permission.CONTAINERS_VIEW)
public class StatsController {

    @Inject
    ResourceCounterService resourceCounterService;

    @Inject
    MemoryGuardService memoryGuardService;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    // Cached CPU snapshot for delta calculation (avoids Thread.sleep)
    private volatile long[] lastCpuSnapshot;

    @PostConstruct
    void init() {
        // Seed initial CPU snapshot so the first call has a baseline
        try { lastCpuSnapshot = readCpuSnapshot(); } catch (Exception ignored) {}
    }

    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> getInfo() {
        return Map.of("version", appVersion);
    }

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(resourceCounterService.getAll());
        result.put("startedAt", resourceCounterService.getStartedAt());
        return result;
    }

    @GET
    @Path("/host")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getHostStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memory", readMemory());
        result.put("cpu", readCpu());
        result.put("disk", readDisk());
        return result;
    }

    private Map<String, Object> readMemory() {
        Map<String, Object> mem = new LinkedHashMap<>();
        HostMemoryStatus status = memoryGuardService.getStatus();
        if (status.supported()) {
            mem.put("totalMb", status.totalMb());
            mem.put("usedMb", status.usedMb());
            mem.put("availableMb", status.availableMb());
            long total = status.totalMb();
            mem.put("usagePercent", total > 0 ? Math.round(status.usedMb() * 100.0 / total) : 0);
        }
        mem.put("guardEnabled", status.enabled());
        if (status.enabled()) {
            mem.put("guardThresholdMb", status.thresholdMb());
        }
        return mem;
    }

    private Map<String, Object> readCpu() {
        Map<String, Object> cpu = new LinkedHashMap<>();
        try {
            long[] current = readCpuSnapshot();
            if (current != null && lastCpuSnapshot != null) {
                long totalDelta = current[0] - lastCpuSnapshot[0];
                long idleDelta = current[1] - lastCpuSnapshot[1];
                int usagePercent = totalDelta > 0 ? (int) Math.round((totalDelta - idleDelta) * 100.0 / totalDelta) : 0;
                cpu.put("usagePercent", Math.max(0, Math.min(100, usagePercent)));
                cpu.put("cores", Runtime.getRuntime().availableProcessors());
            }
            if (current != null) {
                lastCpuSnapshot = current;
            }
        } catch (Exception e) {
            Log.debugf("Failed to read /proc/stat: %s", e.getMessage());
        }
        return cpu;
    }

    /** Returns [totalTime, idleTime] from the first line of /proc/stat */
    private long[] readCpuSnapshot() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(java.nio.file.Path.of("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.split("\\s+");
            if (parts.length < 5) return null;
            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq;
            return new long[]{total, idle + iowait};
        }
    }

    private Map<String, Object> readDisk() {
        Map<String, Object> disk = new LinkedHashMap<>();
        try {
            java.io.File root = new java.io.File("/");
            long totalBytes = root.getTotalSpace();
            long freeBytes = root.getUsableSpace();
            long usedBytes = totalBytes - freeBytes;
            long totalGb = totalBytes / (1024 * 1024 * 1024);
            long usedGb = usedBytes / (1024 * 1024 * 1024);
            long availableGb = freeBytes / (1024 * 1024 * 1024);
            disk.put("totalGb", totalGb);
            disk.put("usedGb", usedGb);
            disk.put("availableGb", availableGb);
            disk.put("usagePercent", totalBytes > 0 ? Math.round(usedBytes * 100.0 / totalBytes) : 0);
        } catch (Exception e) {
            Log.debugf("Failed to read disk stats: %s", e.getMessage());
        }
        return disk;
    }
}
