package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatabaseActivityPollerTest {

    @Mock DatabaseService databaseService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;

    @InjectMocks DatabaseActivityPoller poller;

    @BeforeEach
    void setUp() {
        poller.enabled = true;
        poller.intervalSeconds = 60;
        poller.initialDelaySeconds = 30;
    }

    @Test
    void runCycle_skipsRepositoriesWithoutDatabaseConfig() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo-with-db", "repo-without-db"));
        when(databaseService.hasDatabaseConfig("repo-with-db")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("repo-without-db")).thenReturn(false);
        when(databaseService.getClientBackendActivity("repo-with-db"))
                .thenReturn(Map.of("db1", Instant.parse("2026-05-29T10:00:00Z")));

        poller.runCycle();

        verify(databaseService).getClientBackendActivity("repo-with-db");
        verify(databaseService, never()).getClientBackendActivity("repo-without-db");
        verify(managedDatabaseRepository).bulkMarkUsed(eq("repo-with-db"), any());
        verify(managedDatabaseRepository, never()).bulkMarkUsed(eq("repo-without-db"), any());
    }

    @Test
    void runCycle_emptyActivityResult_skipsBulkWrite() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo1"));
        when(databaseService.hasDatabaseConfig("repo1")).thenReturn(true);
        when(databaseService.getClientBackendActivity("repo1")).thenReturn(Map.of());

        poller.runCycle();

        verify(managedDatabaseRepository, never()).bulkMarkUsed(anyString(), any());
    }

    @Test
    void runCycle_perRepositoryIsolation_oneBadRepoDoesNotBreakOthers() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("bad", "good"));
        when(databaseService.hasDatabaseConfig(anyString())).thenReturn(true);
        when(databaseService.getClientBackendActivity("bad"))
                .thenThrow(new RuntimeException("pg unreachable"));
        when(databaseService.getClientBackendActivity("good"))
                .thenReturn(Map.of("db1", Instant.parse("2026-05-29T10:00:00Z")));

        poller.runCycle();

        // The bad repository must NOT prevent the good one from being processed.
        verify(managedDatabaseRepository).bulkMarkUsed(eq("good"), any());
        verify(managedDatabaseRepository, never()).bulkMarkUsed(eq("bad"), any());
    }

    @Test
    void runCycle_repositoryListFailure_returnsCleanly() {
        when(allowedRepositoryResolver.getAllowed()).thenThrow(new RuntimeException("config error"));

        poller.runCycle();

        verifyNoInteractions(databaseService, managedDatabaseRepository);
    }

    @Test
    void runCycle_passesActivityMapVerbatimToBulkMarkUsed() {
        Map<String, Instant> activity = Map.of(
                "db1", Instant.parse("2026-05-29T10:00:00Z"),
                "db2", Instant.parse("2026-05-29T11:00:00Z"));
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo1"));
        when(databaseService.hasDatabaseConfig("repo1")).thenReturn(true);
        when(databaseService.getClientBackendActivity("repo1")).thenReturn(activity);

        poller.runCycle();

        verify(managedDatabaseRepository).bulkMarkUsed("repo1", activity);
    }
}
