package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagedDatabaseUsageTrackerTest {

    @Mock ManagedDatabaseRepository repository;
    @Mock ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    @InjectMocks ManagedDatabaseUsageTracker tracker;

    @Test
    void markUsed_blankRepository_isNoop() {
        tracker.markUsed("", "mydb");
        tracker.markUsed(null, "mydb");
        verifyNoInteractions(repository, listManagedDatabasesUseCase);
    }

    @Test
    void markUsed_blankDatabaseName_isNoop() {
        tracker.markUsed("myrepo", "");
        tracker.markUsed("myrepo", null);
        verifyNoInteractions(repository, listManagedDatabasesUseCase);
    }

    @Test
    void markUsed_existingRecord_updatesTimestampAndInvalidatesCache() {
        ManagedDatabase existing = new ManagedDatabase("myrepo", "mydb");
        existing.setAppLastUsedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.find("myrepo", "mydb")).thenReturn(Optional.of(existing));

        Instant when = Instant.parse("2026-05-29T10:00:00Z");
        tracker.markUsed("myrepo", "mydb", when);

        ArgumentCaptor<ManagedDatabase> captor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(repository).save(captor.capture());
        assertEquals(when, captor.getValue().getAppLastUsedAt());
        verify(listManagedDatabasesUseCase).invalidateCache("myrepo");
    }

    @Test
    void markUsed_missingRecord_createsNewWithTimestamp() {
        when(repository.find("myrepo", "newdb")).thenReturn(Optional.empty());

        Instant when = Instant.parse("2026-05-29T10:00:00Z");
        tracker.markUsed("myrepo", "newdb", when);

        ArgumentCaptor<ManagedDatabase> captor = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(repository).save(captor.capture());
        ManagedDatabase saved = captor.getValue();
        assertEquals("myrepo", saved.getRepository());
        assertEquals("newdb", saved.getName());
        assertEquals(when, saved.getAppLastUsedAt());
    }

    @Test
    void markUsed_repositoryFailure_doesNotPropagate() {
        when(repository.find(anyString(), anyString())).thenThrow(new RuntimeException("io error"));

        // Must NOT throw — usage tracking is non-critical and must never break
        // the caller's flow (container start, migration, restore, etc.).
        tracker.markUsed("myrepo", "mydb");

        verify(listManagedDatabasesUseCase, never()).invalidateCache(anyString());
    }

    @Test
    void markUsed_saveFailure_doesNotPropagate() {
        when(repository.find(anyString(), anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("disk full")).when(repository).save(any());

        tracker.markUsed("myrepo", "mydb");

        verify(listManagedDatabasesUseCase, never()).invalidateCache(anyString());
    }
}
