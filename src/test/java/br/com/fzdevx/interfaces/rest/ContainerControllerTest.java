package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.domain.model.HostMemoryStatus;
import br.com.fzdevx.domain.shared.Constants;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.docker.ContainerProtectionService;
import br.com.fzdevx.infrastructure.docker.MemoryGuardService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import br.com.fzdevx.application.usecase.RestoreDumpUseCase;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerControllerTest {

    @Mock DockerClient dockerClient;
    @Mock ContainerExpirationService expirationService;
    @Mock ContainerProtectionService protectionService;
    @Mock br.com.fzdevx.infrastructure.docker.ContainerVisibilityService visibilityService;
    @Mock MemoryGuardService memoryGuardService;
    @Mock ContainerListBroadcaster broadcaster;
    @Mock Config config;
    @Mock br.com.fzdevx.application.port.AuditLogger auditLogger;

    @InjectMocks
    ContainerController controller;

    @Mock ListContainersCmd listContainersCmd;

    @BeforeEach
    void setUp() {
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.withShowAll(anyBoolean())).thenReturn(listContainersCmd);
    }

    private Container mockContainer(String id, String image, String name, String status, String command) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        when(c.getImage()).thenReturn(image);
        when(c.getNames()).thenReturn(new String[]{"/" + name});
        when(c.getStatus()).thenReturn(status);
        when(c.getCommand()).thenReturn(command);
        when(c.getCreated()).thenReturn(1700000000L);
        when(c.getPorts()).thenReturn(new ContainerPort[]{});
        when(c.getLabels()).thenReturn(Collections.emptyMap());

        ContainerNetworkSettings netSettings = mock(ContainerNetworkSettings.class);
        when(netSettings.getNetworks()).thenReturn(null);
        when(c.getNetworkSettings()).thenReturn(netSettings);
        return c;
    }

    // ---- getContainers ----

    @Test
    void getContainers_returnsContainerList() {
        Container c = mockContainer("abcdef1234567890", "postgres:16", "my-pg", "Up 2 hours", "/docker-entrypoint");
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(1, result.size());
        assertEquals("abcdef1234", result.getFirst().getContainerId());
        assertEquals("my-pg", result.getFirst().getNames());
        assertEquals("postgres:16", result.getFirst().getImage());
    }

    @Test
    void getContainers_filtersOutSelfImage() {
        Container self = mockContainer("self12345678", Constants.DOCKER_WEB_HANDLER_IMAGE, "self", "Up", "/app");
        Container other = mockContainer("abcdef1234567890", "postgres:16", "pg", "Up", "/docker-entry");
        when(listContainersCmd.exec()).thenReturn(List.of(self, other));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(1, result.size());
        assertEquals("pg", result.getFirst().getNames());
    }

    @Test
    void getContainers_filtersOutSelfImageWithTag() {
        // The app's own image is reported with a tag (e.g. :0.18) and must still be filtered out.
        Container self = mockContainer("self12345678", Constants.DOCKER_WEB_HANDLER_IMAGE + ":0.18", "self", "Up", "/app");
        Container other = mockContainer("abcdef1234567890", "postgres:16", "pg", "Up", "/docker-entry");
        when(listContainersCmd.exec()).thenReturn(List.of(self, other));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(1, result.size());
        assertEquals("pg", result.getFirst().getNames());
    }

    @Test
    void getContainers_setsProtectedFlagFromService() {
        Container c = mockContainer("abcdef1234567890", "postgres:16", "pg", "Up", "cmd");
        when(listContainersCmd.exec()).thenReturn(List.of(c));
        when(protectionService.isProtectedImage("postgres:16")).thenReturn(true);

        List<DockerContainer> result = controller.getContainers();

        assertTrue(result.getFirst().isProtectedFlag());
    }

    @Test
    void getContainers_unprotectedImage_flagFalse() {
        Container c = mockContainer("abcdef1234567890", "postgres:16", "pg", "Up", "cmd");
        when(listContainersCmd.exec()).thenReturn(List.of(c));
        when(protectionService.isProtectedImage("postgres:16")).thenReturn(false);

        List<DockerContainer> result = controller.getContainers();

        assertFalse(result.getFirst().isProtectedFlag());
    }

    @Test
    void getContainers_filtersOutEphemeralContainers() {
        Container ephemeral = mockContainer("eph123456789", "postgres:16", "ephemeral", "Up", "/docker");
        when(ephemeral.getLabels()).thenReturn(Map.of(RestoreDumpUseCase.EPHEMERAL_LABEL, "true"));
        Container normal = mockContainer("abcdef1234567890", "redis:7", "my-redis", "Up", "/redis");
        when(listContainersCmd.exec()).thenReturn(List.of(ephemeral, normal));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(1, result.size());
        assertEquals("my-redis", result.getFirst().getNames());
    }

    @Test
    void getContainers_truncatesLongCommand() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "this-is-a-very-long-command-string");
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(15, result.getFirst().getCommand().length());
    }

    @Test
    void getContainers_shortCommandKeptAsIs() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "short");
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        List<DockerContainer> result = controller.getContainers();

        assertEquals("short", result.getFirst().getCommand());
    }

    @org.junit.jupiter.api.BeforeEach
    void injectCurrentUser() {
        // real instance: outside RBAC it grants everything (legacy behavior)
        controller.currentUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        controller.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
        controller.containerTenantGuard = br.com.fzdevx.infrastructure.docker.TestContainerTenantGuard.passthrough();
    }

    @Test
    void getContainers_setsCreatedByFromLabel() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "cmd");
        when(c.getLabels()).thenReturn(Map.of(Constants.CREATED_BY_LABEL, "alice"));
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        assertEquals("alice", controller.getContainers().getFirst().getCreatedBy());
    }

    @Test
    void getContainers_hidesCreatedBy_withoutAuditViewPermission() {
        var rbacUser = new br.com.fzdevx.infrastructure.config.CurrentUser();
        rbacUser.set("u1", "bob", java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_VIEW));
        controller.currentUser = rbacUser;
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "cmd");
        when(c.getLabels()).thenReturn(Map.of(Constants.CREATED_BY_LABEL, "alice"));
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        assertNull(controller.getContainers().getFirst().getCreatedBy());
    }

    @Test
    void getContainers_setsRepositoryFromLabel() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "cmd");
        Map<String, String> labels = new HashMap<>();
        labels.put(Constants.REPOSITORY_LABEL, "postgres");
        when(c.getLabels()).thenReturn(labels);
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        List<DockerContainer> result = controller.getContainers();

        assertEquals("postgres", result.getFirst().getRepository());
    }

    @Test
    void getContainers_setsExpiresAt() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "cmd");
        when(listContainersCmd.exec()).thenReturn(List.of(c));
        java.time.Instant expiry = java.time.Instant.parse("2025-12-31T23:59:00Z");
        br.com.fzdevx.domain.model.ContainerExpiration expiration =
                new br.com.fzdevx.domain.model.ContainerExpiration("abcdef1234", "abcdef1234567890", expiry);
        when(expirationService.snapshotByContainerId()).thenReturn(java.util.Map.of("abcdef1234", expiration));

        List<DockerContainer> result = controller.getContainers();

        assertEquals(expiry.toString(), result.getFirst().getExpiresAt());
    }

    @Test
    void getContainers_emptyList() {
        when(listContainersCmd.exec()).thenReturn(Collections.emptyList());

        List<DockerContainer> result = controller.getContainers();

        assertTrue(result.isEmpty());
    }

    @Test
    void getContainers_noPortsSetsHyphen() {
        Container c = mockContainer("abcdef1234567890", "pg:16", "pg", "Up", "cmd");
        when(c.getPorts()).thenReturn(new ContainerPort[]{});
        when(listContainersCmd.exec()).thenReturn(List.of(c));

        List<DockerContainer> result = controller.getContainers();

        assertEquals("-", result.getFirst().getPorts());
    }

    // ---- stopContainer ----

    @Test
    void stopContainer_validId_returnsTrue() {
        StopContainerCmd cmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(cmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertTrue(controller.stopContainer(req));
        verify(cmd).exec();
    }

    @Test
    void stopContainer_protected_returnsFalseAndDoesNotStop() {
        when(protectionService.isProtectedContainer("abc123def4")).thenReturn(true);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertFalse(controller.stopContainer(req));
        verify(dockerClient, never()).stopContainerCmd(any());
    }

    @Test
    void stopContainer_invalidId_returnsFalse() {
        DockerContainer req = new DockerContainer();
        req.setContainerId("INVALID!");

        assertFalse(controller.stopContainer(req));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void stopContainer_nullId_returnsFalse() {
        DockerContainer req = new DockerContainer();
        req.setContainerId(null);

        assertFalse(controller.stopContainer(req));
    }

    @Test
    void stopContainer_dockerException_returnsFalse() {
        StopContainerCmd cmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new RuntimeException("Docker error"));

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertFalse(controller.stopContainer(req));
    }

    // ---- startContainer ----

    @Test
    @SuppressWarnings("unchecked")
    void startContainer_validId_returns200WithTrue() {
        StartContainerCmd cmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("abc123def4")).thenReturn(cmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        Response response = controller.startContainer(req);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(true, body.get("success"));
        verify(cmd).exec();
    }

    @Test
    void startContainer_invalidId_returns400() {
        DockerContainer req = new DockerContainer();
        req.setContainerId("bad!");

        Response response = controller.startContainer(req);
        assertEquals(400, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startContainer_dockerException_returns200WithFalse() {
        StartContainerCmd cmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("abc123def4")).thenReturn(cmd);
        when(cmd.exec()).thenThrow(new RuntimeException("container already started"));

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        Response response = controller.startContainer(req);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(false, body.get("success"));
        assertEquals("ALREADY_RUNNING", body.get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startContainer_memoryGuardBlocked_returns503WithDetails() {
        when(memoryGuardService.checkMemoryFor(null)).thenReturn("Insufficient host memory.");
        when(memoryGuardService.getStatus()).thenReturn(
                new HostMemoryStatus(true, true, false, 8192, 7000, 1192, 2048));

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        Response response = controller.startContainer(req);
        assertEquals(503, response.getStatus());

        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("MEMORY_GUARD", body.get("code"));
        assertEquals(1192L, body.get("availableMb"));
        assertEquals(2048L, body.get("thresholdMb"));

        verify(dockerClient, never()).startContainerCmd(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startContainer_memoryGuardAllows_startsContainer() {
        when(memoryGuardService.checkMemoryFor(null)).thenReturn(null);
        StartContainerCmd cmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("abc123def4")).thenReturn(cmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        Response response = controller.startContainer(req);
        assertEquals(200, response.getStatus());
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals(true, body.get("success"));
        verify(cmd).exec();
    }

    // ---- removeContainer ----

    @Test
    void removeContainer_validId_returnsTrue() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd("abc123def4")).thenReturn(removeCmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertTrue(controller.removeContainer(req));
        verify(expirationService).remove("abc123def4");
        verify(removeCmd).exec();
    }

    @Test
    void removeContainer_protected_returnsFalseAndDoesNotRemove() {
        when(protectionService.isProtectedContainer("abc123def4")).thenReturn(true);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertFalse(controller.removeContainer(req));
        verify(expirationService, never()).remove(anyString());
        verify(dockerClient, never()).removeContainerCmd(any());
    }

    @Test
    void removeContainer_invalidId_returnsFalse() {
        DockerContainer req = new DockerContainer();
        req.setContainerId("bad!");

        assertFalse(controller.removeContainer(req));
    }

    @Test
    void removeContainer_stopFailsContinuesToRemove() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(stopCmd);
        when(stopCmd.exec()).thenThrow(new RuntimeException("not running"));
        when(dockerClient.removeContainerCmd("abc123def4")).thenReturn(removeCmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertTrue(controller.removeContainer(req));
        verify(removeCmd).exec();
    }

    @Test
    void removeContainer_removeThrows_returnsFalse() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd("abc123def4")).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new RuntimeException("permission denied"));

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        assertFalse(controller.removeContainer(req));
    }

    @Test
    void removeContainer_cancelsExpirationFirst() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd("abc123def4")).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd("abc123def4")).thenReturn(removeCmd);

        DockerContainer req = new DockerContainer();
        req.setContainerId("abc123def4");

        controller.removeContainer(req);

        var inOrder = inOrder(expirationService, dockerClient);
        inOrder.verify(expirationService).remove("abc123def4");
        inOrder.verify(dockerClient).stopContainerCmd("abc123def4");
    }
}
