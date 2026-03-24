package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsControllerTest {

    @Mock ResourceCounterService resourceCounterService;
    @Mock MemoryGuardService memoryGuardService;

    @InjectMocks
    StatsController controller;

    @BeforeEach
    void setUp() throws Exception {
        // Trigger @PostConstruct manually
        var initMethod = StatsController.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(controller);
    }

    // ---- getSummary ----

    @Test
    void getSummary_includesCountersAndStartedAt() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("containers", 10L);
        counters.put("imagesDeleted", 5L);
        when(resourceCounterService.getAll()).thenReturn(counters);

        Map<String, Object> result = controller.getSummary();

        assertEquals(10L, result.get("containers"));
        assertEquals(5L, result.get("imagesDeleted"));
        assertNotNull(result.get("startedAt"));
    }

    @Test
    void getSummary_startedAtIsIsoFormat() {
        when(resourceCounterService.getAll()).thenReturn(Map.of());
        Map<String, Object> result = controller.getSummary();
        String startedAt = (String) result.get("startedAt");
        assertDoesNotThrow(() -> java.time.Instant.parse(startedAt));
    }

    // ---- getHostStats ----

    @Test
    void getHostStats_containsAllSections() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, false, true, 16384, 8192, 8192, 2048));

        Map<String, Object> result = controller.getHostStats();

        assertTrue(result.containsKey("memory"));
        assertTrue(result.containsKey("cpu"));
        assertTrue(result.containsKey("disk"));
    }

    // ---- readMemory (via getHostStats) ----

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_usesMemoryGuardService() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, false, true, 16384, 8192, 8192, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertEquals(16384L, memory.get("totalMb"));
        assertEquals(8192L, memory.get("usedMb"));
        assertEquals(8192L, memory.get("availableMb"));
        assertEquals(50L, memory.get("usagePercent"));
        assertEquals(false, memory.get("guardEnabled"));
        assertNull(memory.get("guardThresholdMb"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_guardEnabled_includesThreshold() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, true, true, 16384, 10240, 6144, 2048));
        when(memoryGuardService.isEnabled()).thenReturn(true);

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertEquals(true, memory.get("guardEnabled"));
        assertEquals(2048L, memory.get("guardThresholdMb"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_guardDisabled_noThreshold() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, false, true, 16384, 8192, 8192, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertEquals(false, memory.get("guardEnabled"));
        assertNull(memory.get("guardThresholdMb"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_unsupported_returnsEmptyWithGuardFlag() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(false, false, true, 0, 0, 0, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertNull(memory.get("totalMb"));
        assertNull(memory.get("usedMb"));
        assertEquals(false, memory.get("guardEnabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_fullUsage_returns100Percent() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, false, false, 16384, 16384, 0, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertEquals(100L, memory.get("usagePercent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_memory_zeroTotal_returns0Percent() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, false, true, 0, 0, 0, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> memory = (Map<String, Object>) result.get("memory");

        assertEquals(0L, memory.get("usagePercent"));
    }

    // ---- readDisk (via getHostStats) ----

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_disk_returnsNonNegativeValues() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(false, false, true, 0, 0, 0, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> disk = (Map<String, Object>) result.get("disk");

        // Disk values depend on the actual host, but should be non-negative
        assertTrue((long) disk.getOrDefault("totalGb", 0L) >= 0);
        assertTrue((long) disk.getOrDefault("usedGb", 0L) >= 0);
        assertTrue((long) disk.getOrDefault("availableGb", 0L) >= 0);
        assertTrue((long) disk.getOrDefault("usagePercent", 0L) >= 0);
        assertTrue((long) disk.getOrDefault("usagePercent", 0L) <= 100);
    }

    // ---- readCpu (via getHostStats) ----

    @Test
    @SuppressWarnings("unchecked")
    void getHostStats_cpu_returnsValidPercentOrEmpty() {
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(false, false, true, 0, 0, 0, 2048));

        Map<String, Object> result = controller.getHostStats();
        Map<String, Object> cpu = (Map<String, Object>) result.get("cpu");

        if (cpu.containsKey("usagePercent")) {
            int percent = (int) cpu.get("usagePercent");
            assertTrue(percent >= 0 && percent <= 100, "CPU percent should be 0-100, got: " + percent);
            assertTrue((int) cpu.get("cores") > 0);
        }
        // On non-Linux hosts, cpu map may be empty — that's fine
    }
}
