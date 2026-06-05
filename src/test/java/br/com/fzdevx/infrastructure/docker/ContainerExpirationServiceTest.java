package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContainerExpirationServiceTest {

    @Mock DockerClient dockerClient;
    @Mock ExpirationRepository expirationRepository;
    @Mock DatabaseService databaseService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock ContainerSchedulingService schedulingService;
    @Mock ContainerProtectionService protectionService;
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

    // ---- updateExpiration ----

    @Test
    void updateExpiration_existingRecord_updatesTimeAndFlag() {
        Instant original = Instant.now().plusSeconds(3600);
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", original, "repo", "mydb", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        Instant newTime = Instant.now().plusSeconds(7200);
        boolean result = service.updateExpiration("abc123def4", newTime, true);

        assertTrue(result);
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertEquals(newTime, saved.getExpiresAt());
        assertTrue(saved.isDeleteDatabaseOnExpiration());
    }

    @Test
    void updateExpiration_existingRecord_removeExpiration() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "repo", "mydb", true);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.updateExpiration("abc123def4", null, false);

        assertTrue(result);
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertNull(saved.getExpiresAt());
        assertFalse(saved.isDeleteDatabaseOnExpiration());
    }

    @Test
    void updateExpiration_noRecord_nullExpires_returnsFalse() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        boolean result = service.updateExpiration("abc123def4", null, false);

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void updateExpiration_noRecord_createsNewViaDocker() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        Container dockerContainer = mock(Container.class);
        when(dockerContainer.getId()).thenReturn("abc123def4fullid1234567890");
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(dockerContainer));

        Instant future = Instant.now().plusSeconds(3600);
        boolean result = service.updateExpiration("abc123def4", future, false);

        assertTrue(result);
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        ContainerExpiration saved = captor.getValue();
        assertEquals("abc123def4", saved.getShortId());
        assertEquals("abc123def4fullid1234567890", saved.getFullContainerId());
        assertEquals(future, saved.getExpiresAt());
        assertFalse(saved.isDeleteDatabaseOnExpiration());
    }

    @Test
    void updateExpiration_noRecord_dockerContainerNotFound_returnsFalse() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(Collections.emptyList());

        Instant future = Instant.now().plusSeconds(3600);
        boolean result = service.updateExpiration("abc123def4", future, false);

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void updateExpiration_noRecord_dockerException_returnsFalse() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("Docker unavailable"));

        Instant future = Instant.now().plusSeconds(3600);
        boolean result = service.updateExpiration("abc123def4", future, false);

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    // ---- enableDatabaseDeletion ----

    @Test
    void enableDatabaseDeletion_validRecord_setsFlag() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600),
                "repo", "mydb", false);
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.enableDatabaseDeletion("abc123def4");

        assertTrue(result);
        ArgumentCaptor<ContainerExpiration> captor = ArgumentCaptor.forClass(ContainerExpiration.class);
        verify(expirationRepository).save(captor.capture());
        assertTrue(captor.getValue().isDeleteDatabaseOnExpiration());
    }

    @Test
    void enableDatabaseDeletion_noExpiresAt_returnsFalse() {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId("abc123def4");
        expiration.setDatabaseName("mydb");
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.enableDatabaseDeletion("abc123def4");

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void enableDatabaseDeletion_noDatabaseName_returnsFalse() {
        ContainerExpiration expiration = new ContainerExpiration(
                "abc123def4", "abc123def4full", Instant.now().plusSeconds(3600));
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.of(expiration));

        boolean result = service.enableDatabaseDeletion("abc123def4");

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
    }

    @Test
    void enableDatabaseDeletion_notFound_returnsFalse() {
        when(expirationRepository.findByContainerId("abc123def4")).thenReturn(Optional.empty());

        boolean result = service.enableDatabaseDeletion("abc123def4");

        assertFalse(result);
        verify(expirationRepository, never()).save(any());
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

    // ---- removeContainersByDatabase ----

    @Test
    void removeContainersByDatabase_skipsProtectedContainer() {
        ContainerExpiration protectedExp = new ContainerExpiration(
                "prot123456", "prot123456full", Instant.now().plusSeconds(3600),
                "myrepo", "mydb", false);
        when(expirationRepository.findByDatabaseName("mydb")).thenReturn(List.of(protectedExp));
        when(protectionService.isProtectedContainer("prot123456full")).thenReturn(true);

        service.removeContainersByDatabase("mydb", null);

        verify(dockerClient, never()).stopContainerCmd(anyString());
        verify(dockerClient, never()).removeContainerCmd(anyString());
        verify(expirationRepository, never()).delete("prot123456");
    }

    @Test
    void removeContainersByDatabase_removesUnprotectedContainer() {
        ContainerExpiration exp = new ContainerExpiration(
                "norm123456", "norm123456full", Instant.now().plusSeconds(3600),
                "myrepo", "mydb", false);
        when(expirationRepository.findByDatabaseName("mydb")).thenReturn(List.of(exp));

        StopContainerCmd stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("norm123456full")).thenReturn(stopCmd);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd("norm123456full")).thenReturn(removeCmd);

        service.removeContainersByDatabase("mydb", null);

        verify(removeCmd).exec();
        verify(expirationRepository).delete("norm123456");
    }
}
