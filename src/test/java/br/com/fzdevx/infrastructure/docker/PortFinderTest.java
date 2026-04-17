package br.com.fzdevx.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
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

    private PortFinder portFinder;

    @BeforeEach
    void setUp() throws Exception {
        portFinder = new PortFinder();
        setField(portFinder, "dockerClient", dockerClient);
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
}
