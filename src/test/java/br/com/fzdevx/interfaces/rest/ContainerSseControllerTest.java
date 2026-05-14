package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.RemoveContainerRequest;
import br.com.fzdevx.application.usecase.RemoveContainerUseCase;
import br.com.fzdevx.application.usecase.RunContainerUseCase;
import br.com.fzdevx.application.usecase.RunMigrationUseCase;
import br.com.fzdevx.application.usecase.StreamContainerLogsUseCase;
import br.com.fzdevx.application.usecase.StreamContainerStatsUseCase;
import br.com.fzdevx.application.usecase.UpgradeContainerUseCase;
import br.com.fzdevx.infrastructure.config.RequestStash;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.webhook.WebhookService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerSseControllerTest {

    @Mock RequestStash requestStash;
    @Mock RunContainerUseCase runContainerUseCase;
    @Mock RemoveContainerUseCase removeContainerUseCase;
    @Mock RunMigrationUseCase runMigrationUseCase;
    @Mock UpgradeContainerUseCase upgradeContainerUseCase;
    @Mock DumpStorageService dumpStorageService;
    @Mock StreamContainerLogsUseCase streamContainerLogsUseCase;
    @Mock StreamContainerStatsUseCase streamContainerStatsUseCase;
    @Mock WebhookService webhookService;
    @Mock ContainerListBroadcaster broadcaster;

    @InjectMocks
    ContainerSseController controller;

    // ---- prepareRemove ----

    @Test
    void prepareRemove_invalidContainerId_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("INVALID!");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withoutDeleteDatabase_stashesAndReturnsTicket() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(false);

        when(requestStash.stashRemove(any())).thenReturn("ticket-123");

        Response res = controller.prepareRemove(req);

        assertEquals(200, res.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) res.getEntity();
        assertEquals("ticket-123", body.get("ticket"));
        verify(dumpStorageService, never()).validateOperationsPassword(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_validPassword_stashesAndReturnsTicket() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("testdb");
        req.setOperationsPassword("correct-pw");

        when(dumpStorageService.validateOperationsPassword("correct-pw")).thenReturn(true);
        when(requestStash.stashRemove(any())).thenReturn("ticket-456");

        Response res = controller.prepareRemove(req);

        assertEquals(200, res.getStatus());
        ArgumentCaptor<RemoveContainerRequest> captor = ArgumentCaptor.forClass(RemoveContainerRequest.class);
        verify(requestStash).stashRemove(captor.capture());
        assertNull(captor.getValue().getOperationsPassword(), "Password should be cleared before stashing");
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidPassword_returns403() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("testdb");
        req.setOperationsPassword("wrong-pw");

        when(dumpStorageService.validateOperationsPassword("wrong-pw")).thenReturn(false);

        Response res = controller.prepareRemove(req);

        assertEquals(403, res.getStatus());
        verify(requestStash, never()).stashRemove(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_nullRepository_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository(null);
        req.setDatabaseName("testdb");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
        verify(requestStash, never()).stashRemove(any());
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidRepository_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("INVALID_REPO!");
        req.setDatabaseName("testdb");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withDeleteDatabase_nullDatabaseName_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName(null);

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }

    @Test
    void prepareRemove_withDeleteDatabase_invalidDatabaseName_returns400() {
        RemoveContainerRequest req = new RemoveContainerRequest();
        req.setContainerId("abc123def456");
        req.setDeleteDatabase(true);
        req.setRepository("myrepo");
        req.setDatabaseName("123-invalid");

        Response res = controller.prepareRemove(req);

        assertEquals(400, res.getStatus());
    }
}
