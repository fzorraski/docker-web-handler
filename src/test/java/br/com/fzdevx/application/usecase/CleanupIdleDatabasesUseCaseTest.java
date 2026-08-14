package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CleanupIdleDatabasesUseCaseTest {

    private static final String REPO = "myapp";

    @Mock DatabaseService databaseService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock ListManagedDatabasesUseCase listManagedDatabasesUseCase;
    @Mock ResourceCounterService resourceCounterService;

    @InjectMocks
    CleanupIdleDatabasesUseCase useCase;

    @org.junit.jupiter.api.BeforeEach
    void wireTenantVisibility() {
        useCase.tenantVisibility = br.com.fzdevx.infrastructure.config.TestTenantVisibility.passthrough();
    }

    // ---- basic cleanup ----

    @Test
    void cleanup_dropsIdleDatabases() {
        ManagedDatabaseInfo idle = makeDb("old_db", false,
                Instant.now().minus(60, ChronoUnit.DAYS));

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(idle));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(1, deleted);
        verify(databaseService).dropDatabase(REPO, "old_db");
        verify(managedDatabaseRepository).delete(REPO, "old_db");
    }

    @Test
    void cleanup_skipsProtectedDatabases() {
        ManagedDatabaseInfo protectedDb = makeDb("protected_db", true,
                Instant.now().minus(60, ChronoUnit.DAYS));

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(protectedDb));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(0, deleted);
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    @Test
    void cleanup_skipsRecentDatabases() {
        ManagedDatabaseInfo recent = makeDb("recent_db", false,
                Instant.now().minus(5, ChronoUnit.DAYS));

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(recent));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(0, deleted);
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    @Test
    void cleanup_includesNeverUsedDatabases() {
        ManagedDatabaseInfo neverUsed = makeDb("unused_db", false, null);

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(neverUsed));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(1, deleted);
        verify(databaseService).dropDatabase(REPO, "unused_db");
    }

    @Test
    void cleanup_skipsNeverUsedProtectedDatabases() {
        ManagedDatabaseInfo neverUsedProtected = makeDb("safe_db", true, null);

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(neverUsedProtected));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(0, deleted);
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    // ---- error handling ----

    @Test
    void cleanup_continuesAfterDropFailure() {
        ManagedDatabaseInfo db1 = makeDb("db1", false, null);
        ManagedDatabaseInfo db2 = makeDb("db2", false, null);

        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(db1, db2));
        doThrow(new RuntimeException("drop failed")).when(databaseService).dropDatabase(REPO, "db1");

        int deleted = useCase.cleanup(REPO, 1);

        assertEquals(1, deleted); // db2 still deleted
        verify(databaseService).dropDatabase(REPO, "db1");
        verify(databaseService).dropDatabase(REPO, "db2");
    }

    @Test
    void cleanup_returnsZero_whenNoDatabases() {
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of());

        assertEquals(0, useCase.cleanup(REPO, 30));
    }

    // ---- mixed scenarios ----

    @Test
    void cleanup_mixedDatabases_correctCount() {
        ManagedDatabaseInfo idle = makeDb("idle", false, Instant.now().minus(60, ChronoUnit.DAYS));
        ManagedDatabaseInfo recent = makeDb("recent", false, Instant.now().minus(5, ChronoUnit.DAYS));
        ManagedDatabaseInfo protectedIdle = makeDb("protected", true, Instant.now().minus(60, ChronoUnit.DAYS));
        ManagedDatabaseInfo neverUsed = makeDb("unused", false, null);

        when(listManagedDatabasesUseCase.listDatabases(REPO))
                .thenReturn(List.of(idle, recent, protectedIdle, neverUsed));

        int deleted = useCase.cleanup(REPO, 30);

        assertEquals(2, deleted); // idle + neverUsed
        verify(databaseService).dropDatabase(REPO, "idle");
        verify(databaseService).dropDatabase(REPO, "unused");
        verify(databaseService, never()).dropDatabase(REPO, "recent");
        verify(databaseService, never()).dropDatabase(REPO, "protected");
    }

    @Test
    void cleanup_skipsDatabasesInUseByContainers() {
        ManagedDatabaseInfo inUse = new ManagedDatabaseInfo("in_use_db", REPO, 1024L, 0, null, null,
                null, false, Instant.now(), null, 1, null, false, null, null, null, null, null, false);
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(inUse));

        int deleted = useCase.cleanup(REPO, 1);

        assertEquals(0, deleted);
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    @Test
    void cleanup_skipsDatabasesWithActiveConnections() {
        ManagedDatabaseInfo withConns = new ManagedDatabaseInfo("active_db", REPO, 1024L, 5, null, null,
                null, false, Instant.now(), null, 0, null, false, null, null, null, null, null, false);
        when(listManagedDatabasesUseCase.listDatabases(REPO)).thenReturn(List.of(withConns));

        int deleted = useCase.cleanup(REPO, 1);

        assertEquals(0, deleted);
        verify(databaseService, never()).dropDatabase(any(), any());
    }

    // ---- helpers ----

    private ManagedDatabaseInfo makeDb(String name, boolean protectedFlag, Instant effectiveLastUsedAt) {
        return new ManagedDatabaseInfo(name, REPO, 1024L, 0, null, null,
                effectiveLastUsedAt, protectedFlag, Instant.now(), null, 0, null, false, null, null, null, null, null, false);
    }
}
