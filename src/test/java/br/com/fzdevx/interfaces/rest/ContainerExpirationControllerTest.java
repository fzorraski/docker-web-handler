package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.DockerContainer;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerExpirationControllerTest {

    @Mock DockerClient dockerClient;
    @Mock ContainerExpirationService expirationService;

    @InjectMocks
    ContainerExpirationController controller;

    private DockerContainer req(String id) {
        DockerContainer dc = new DockerContainer();
        dc.setContainerId(id);
        return dc;
    }

    // ---- extendExpiration ----

    @Test
    void extendExpiration_invalidId_returnsFalse() {
        assertFalse(controller.extendExpiration(req("BAD!"), 10));
    }

    @Test
    void extendExpiration_minutesTooLow_returnsFalse() {
        assertFalse(controller.extendExpiration(req("abc123def4"), 0));
    }

    @Test
    void extendExpiration_minutesTooHigh_returnsFalse() {
        assertFalse(controller.extendExpiration(req("abc123def4"), 1441));
    }

    @Test
    void extendExpiration_valid_delegates() {
        when(expirationService.extendExpiration("abc123def4", 10)).thenReturn(true);
        assertTrue(controller.extendExpiration(req("abc123def4"), 10));
    }

    @Test
    void extendExpiration_serviceFails_returnsFalse() {
        when(expirationService.extendExpiration("abc123def4", 10)).thenReturn(false);
        assertFalse(controller.extendExpiration(req("abc123def4"), 10));
    }

    // ---- cancelDatabaseDeletion ----

    @Test
    void cancelDatabaseDeletion_invalidId_returnsFalse() {
        assertFalse(controller.cancelDatabaseDeletion(req("BAD!")));
    }

    @Test
    void cancelDatabaseDeletion_valid_delegates() {
        when(expirationService.disableDatabaseDeletion("abc123def4")).thenReturn(true);
        assertTrue(controller.cancelDatabaseDeletion(req("abc123def4")));
    }

    // ---- cancelExpiration ----

    @Test
    void cancelExpiration_invalidId_returnsFalse() {
        assertFalse(controller.cancelExpiration(req("BAD!")));
    }

    @Test
    void cancelExpiration_valid_cancelsAndReturnsTrue() {
        assertTrue(controller.cancelExpiration(req("abc123def4")));
        verify(expirationService).cancel("abc123def4");
    }

    // ---- getDatabaseConflicts ----

    @Test
    void getDatabaseConflicts_invalidDbName_returnsEmpty() {
        var result = controller.getDatabaseConflicts("123bad");
        assertTrue(result.getInUseByContainers().isEmpty());
        assertNull(result.getScheduledForDeletionBy());
    }

    @Test
    void getDatabaseConflicts_noExpirations_returnsEmpty() {
        when(expirationService.findByDatabaseName("mydb")).thenReturn(Collections.emptyList());
        var result = controller.getDatabaseConflicts("mydb");
        assertTrue(result.getInUseByContainers().isEmpty());
    }
}
