package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerExpirationServiceTest {

    @Mock DockerClient dockerClient;
    @Mock ExpirationRepository expirationRepository;
    @Mock DatabaseService databaseService;
    @Mock ContainerSchedulingService schedulingService;
    @Mock ContainerListBroadcaster broadcaster;

    @InjectMocks
    ContainerExpirationService service;

    // ---- cancel ----

    @Test
    void cancel_withDatabaseName_preservesMetadata() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "myrepo", "mydb", true);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        service.cancel("abc123def4");

        verify(expirationRepository, never()).delete("abc123def4");

        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertNull(saved.getExpiresAt());
        assertFalse(saved.isDeleteDatabaseOnExpiration());
        assertEquals("mydb", saved.getDatabaseName());
        assertEquals("myrepo", saved.getRepository());
    }

    @Test
    void cancel_withoutDatabaseName_deletesRecord() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600));
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        service.cancel("abc123def4");

        verify(expirationRepository).delete("abc123def4");
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void cancel_withBlankDatabaseName_deletesRecord() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "myrepo", "  ", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        service.cancel("abc123def4");

        verify(expirationRepository).delete("abc123def4");
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void cancel_recordNotFound_deletesById() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        service.cancel("abc123def4");

        verify(expirationRepository).delete("abc123def4");
        verify(expirationRepository, never()).save(any());
    }

    // ---- remove ----

    @Test
    void remove_withDatabaseName_deletesRecord() {
        service.remove("abc123def4");

        verify(expirationRepository).delete("abc123def4");
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void remove_metadataOnlyRecord_deletesRecord() {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId("abc123def4");
        expiration.setFullContainerId("abc123def4full");
        expiration.setRepository("myrepo");
        expiration.setDatabaseName("mydb");
        // metadata-only record — remove() should still delete it
        service.remove("abc123def4");

        verify(expirationRepository).delete("abc123def4");
        verify(expirationRepository, never()).save(any());
    }

    // ---- extendExpiration ----

    @Test
    void extendExpiration_withActiveExpiration_extendsAndReschedules() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "myrepo", "mydb", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.extendExpiration("abc123def4", 10);

        assertTrue(result);
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertNotNull(saved.getExpiresAt());
    }

    @Test
    void extendExpiration_metadataOnlyRecord_returnsFalse() {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId("abc123def4");
        expiration.setFullContainerId("abc123def4full");
        expiration.setRepository("myrepo");
        expiration.setDatabaseName("mydb");
        // expiresAt is null — cancel() was called previously
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.extendExpiration("abc123def4", 10);

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void extendExpiration_recordNotFound_returnsFalse() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        boolean result = service.extendExpiration("abc123def4", 10);

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    // ---- cancel then remove lifecycle ----

    @Test
    void cancel_thenRemove_deletesPreservedRecord() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "myrepo", "mydb", true);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        // Step 1: cancel — preserves metadata
        service.cancel("abc123def4");
        verify(expirationRepository, never()).delete("abc123def4");
        verify(expirationRepository).save(any());

        // Step 2: remove — fully deletes
        service.remove("abc123def4");
        verify(expirationRepository).delete("abc123def4");
    }

    // ---- cancel_metadataOnly ----

    @Test
    void cancel_metadataOnly_preservesRecord() {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId("abc123def4");
        expiration.setFullContainerId("abc123def4full");
        expiration.setRepository("myrepo");
        expiration.setDatabaseName("mydb");
        // expiresAt is null (metadata-only record)
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        service.cancel("abc123def4");

        verify(expirationRepository, never()).delete("abc123def4");
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertNull(saved.getExpiresAt());
        assertEquals("mydb", saved.getDatabaseName());
    }
}
