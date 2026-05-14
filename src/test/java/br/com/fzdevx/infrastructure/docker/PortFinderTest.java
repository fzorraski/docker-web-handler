package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortFinderTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ListContainersCmd listContainersCmd;

    @Mock
    Config config;

    private PortFinder portFinder;

    @BeforeEach
    void setUp() throws Exception {
        portFinder = new PortFinder();
        setField(portFinder, "dockerClient", dockerClient);
        setField(portFinder, "config", config);
        setField(portFinder, "portMappingEnabled", true);
        setField(portFinder, "hostPortStart", 50000);

        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(true)).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void findAvailablePorts_reservesPorts() {
        List<Integer> first = portFinder.findAvailablePorts(2);
        List<Integer> second = portFinder.findAvailablePorts(2);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(Collections.disjoint(first, second),
                "Second call should not return any ports from the first call");
    }

    @Test
    void releasePorts_makesPortsAvailableAgain() {
        List<Integer> first = portFinder.findAvailablePorts(2);
        portFinder.releasePorts(first);
        List<Integer> second = portFinder.findAvailablePorts(2);

        assertEquals(first, second, "After release, same ports should be returned");
    }

    @Test
    void releasePorts_withNull_doesNotThrow() {
        assertDoesNotThrow(() -> portFinder.releasePorts(null));
    }

    @Test
    void releasePorts_withUnknownPorts_doesNotThrow() {
        assertDoesNotThrow(() -> portFinder.releasePorts(List.of(99999)));
    }

    @Test
    void findAvailablePorts_concurrentCalls_noDuplicates() throws Exception {
        int threadCount = 5;
        int portsPerThread = 2;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<List<Integer>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                return portFinder.findAvailablePorts(portsPerThread);
            }));
        }

        ready.await();
        go.countDown();

        Set<Integer> allPorts = new HashSet<>();
        for (Future<List<Integer>> future : futures) {
            List<Integer> ports = future.get();
            assertEquals(portsPerThread, ports.size());
            for (int port : ports) {
                assertTrue(allPorts.add(port),
                        "Duplicate port detected across concurrent calls: " + port);
            }
        }

        assertEquals(threadCount * portsPerThread, allPorts.size());
        executor.shutdown();

        // Clean up reservations
        portFinder.releasePorts(new ArrayList<>(allPorts));
    }

    // ---- findAvailablePortsPreferring ----

    @Test
    void findAvailablePortsPreferring_usesPreferredWhenFree() {
        List<Integer> result = portFinder.findAvailablePortsPreferring(List.of(50000, 50001), 2);
        assertEquals(List.of(50000, 50001), result);
        portFinder.releasePorts(result);
    }

    @Test
    void findAvailablePortsPreferring_fallsBackWhenPreferredReserved() {
        // Reserve 2 ports first
        List<Integer> reserved = portFinder.findAvailablePorts(2);
        assertEquals(2, reserved.size());

        // Now ask for those same ports as preferred — they should be skipped
        List<Integer> result = portFinder.findAvailablePortsPreferring(reserved, 2);
        assertEquals(2, result.size());
        assertTrue(Collections.disjoint(reserved, result),
                "Preferred ports were reserved, so result should not contain any of them");

        portFinder.releasePorts(reserved);
        portFinder.releasePorts(result);
    }

    @Test
    void findAvailablePortsPreferring_mixesPreferredAndScanned() {
        // Reserve one port first
        List<Integer> reserved = portFinder.findAvailablePorts(1);
        int reservedPort = reserved.get(0);

        // Get another free port to use as a second preferred
        List<Integer> secondPort = portFinder.findAvailablePorts(1);
        int freePreferred = secondPort.get(0);
        portFinder.releasePorts(secondPort);

        // Ask for 2 ports preferring [reservedPort, freePreferred]
        List<Integer> result = portFinder.findAvailablePortsPreferring(List.of(reservedPort, freePreferred), 2);
        assertEquals(2, result.size());
        // freePreferred should be used (it was released), reservedPort should be skipped
        assertTrue(result.contains(freePreferred),
                "Free preferred port should be in result");
        assertFalse(result.contains(reservedPort),
                "Reserved port should not be in result");

        portFinder.releasePorts(reserved);
        portFinder.releasePorts(result);
    }

    @Test
    void findAvailablePortsPreferring_emptyPreferred_scansNormally() {
        List<Integer> result = portFinder.findAvailablePortsPreferring(Collections.emptyList(), 2);
        assertEquals(2, result.size());
        portFinder.releasePorts(result);
    }

    @Test
    void findAvailablePortsPreferring_zeroCount_returnsEmpty() {
        List<Integer> result = portFinder.findAvailablePortsPreferring(List.of(50000), 0);
        assertTrue(result.isEmpty());
    }

    // ---- per-repo port-mapping.enabled ----

    @Test
    void getContainerPorts_perRepoEnabled_overridesGlobalDisabled() throws Exception {
        setField(portFinder, "portMappingEnabled", false);
        when(config.getOptionalValue("repository.port-mapping.enabled.myapp", Boolean.class))
                .thenReturn(Optional.of(true));
        when(config.getOptionalValue("repository.container-ports.myapp", String.class))
                .thenReturn(Optional.of("8080"));

        List<Integer> ports = portFinder.getContainerPorts("myapp");
        assertEquals(List.of(8080), ports);
    }

    @Test
    void getContainerPorts_perRepoDisabled_overridesGlobalEnabled() {
        when(config.getOptionalValue("repository.port-mapping.enabled.myapp", Boolean.class))
                .thenReturn(Optional.of(false));

        List<Integer> ports = portFinder.getContainerPorts("myapp");
        assertTrue(ports.isEmpty());
    }

    @Test
    void getContainerPorts_noPerRepo_fallsBackToGlobal() {
        when(config.getOptionalValue("repository.port-mapping.enabled.myapp", Boolean.class))
                .thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.container-ports.myapp", String.class))
                .thenReturn(Optional.of("8080"));

        List<Integer> ports = portFinder.getContainerPorts("myapp");
        assertEquals(List.of(8080), ports);
    }

    // ---- per-repo host-port-start ----

    @Test
    void getHostPortStart_perRepo_returnsPerRepoValue() {
        when(config.getOptionalValue("repository.port-mapping.host-port-start.myapp", Integer.class))
                .thenReturn(Optional.of(9000));

        assertEquals(9000, portFinder.getHostPortStart("myapp"));
    }

    @Test
    void getHostPortStart_noPerRepo_returnsGlobal() {
        when(config.getOptionalValue("repository.port-mapping.host-port-start.myapp", Integer.class))
                .thenReturn(Optional.empty());

        assertEquals(50000, portFinder.getHostPortStart("myapp"));
    }

    @Test
    void findAvailablePorts_withCustomStartPort_startsFromGivenPort() {
        List<Integer> ports = portFinder.findAvailablePorts(2, 60000);
        assertEquals(2, ports.size());
        assertTrue(ports.get(0) >= 60000);
        portFinder.releasePorts(ports);
    }
}
