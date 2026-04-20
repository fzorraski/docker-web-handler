package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListManagedDatabasesUseCaseTest {

    private static final String REPO = "myapp";

    @Mock DatabaseService databaseService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock AllowedRepositoryResolver allowedRepositoryResolver;
    @Mock ContainerExpirationService expirationService;

    @InjectMocks
    ListManagedDatabasesUseCase useCase;

    @BeforeEach
    void setUp() {
        setField("cacheTtlSeconds", 15);
    }

    // ---- getRepositories ----

    @Test
    void getRepositories_filtersWithDbConfig() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo1", "repo2", "repo3"));
        when(databaseService.hasDatabaseConfig("repo1")).thenReturn(true);
        when(databaseService.hasDatabaseConfig("repo2")).thenReturn(false);
        when(databaseService.hasDatabaseConfig("repo3")).thenReturn(true);

        List<String> result = useCase.getRepositories();

        assertEquals(2, result.size());
        assertTrue(result.contains("repo1"));
        assertTrue(result.contains("repo3"));
        assertFalse(result.contains("repo2"));
    }

    @Test
    void getRepositories_empty_whenNoneConfigured() {
        when(allowedRepositoryResolver.getAllowed()).thenReturn(List.of("repo1"));
        when(databaseService.hasDatabaseConfig("repo1")).thenReturn(false);

        assertTrue(useCase.getRepositories().isEmpty());
    }

    // ---- listDatabases ----

    @Test
    void listDatabases_mergesLiveDataWithPersisted() {
        stubDatabaseQueries(List.of("db1", "db2"));
        ManagedDatabase persisted = new ManagedDatabase(REPO, "db1");
        persisted.setProtectedFlag(true);
        persisted.setDescription("important db");
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of(persisted));

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertEquals(2, result.size());

        ManagedDatabaseInfo db1 = result.stream().filter(d -> d.name().equals("db1")).findFirst().orElseThrow();
        assertTrue(db1.protectedFlag());
        assertEquals("important db", db1.description());
        assertEquals(1024L, db1.sizeBytes());

        ManagedDatabaseInfo db2 = result.stream().filter(d -> d.name().equals("db2")).findFirst().orElseThrow();
        assertFalse(db2.protectedFlag());
        assertNull(db2.description());
    }

    @Test
    void listDatabases_autoCreatesMetadataForNewDatabases() {
        stubDatabaseQueries(List.of("newdb"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertEquals(1, result.size());
        verify(managedDatabaseRepository).save(argThat(md ->
                md.getName().equals("newdb") && md.getRepository().equals(REPO)));
    }

    @Test
    void listDatabases_computesEffectiveLastUsedAt_pgMoreRecent() {
        stubDatabaseQueries(List.of("db1"));
        Instant pgActivity = Instant.now().minusSeconds(60);
        Instant appActivity = Instant.now().minusSeconds(3600);
        when(databaseService.getLastActivityTimes(REPO)).thenReturn(Map.of("db1", pgActivity));
        ManagedDatabase md = new ManagedDatabase(REPO, "db1");
        md.setAppLastUsedAt(appActivity);
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of(md));

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertEquals(pgActivity, result.getFirst().effectiveLastUsedAt());
    }

    @Test
    void listDatabases_computesEffectiveLastUsedAt_appMoreRecent() {
        stubDatabaseQueries(List.of("db1"));
        Instant pgActivity = Instant.now().minusSeconds(3600);
        Instant appActivity = Instant.now().minusSeconds(60);
        when(databaseService.getLastActivityTimes(REPO)).thenReturn(Map.of("db1", pgActivity));
        ManagedDatabase md = new ManagedDatabase(REPO, "db1");
        md.setAppLastUsedAt(appActivity);
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of(md));

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertEquals(appActivity, result.getFirst().effectiveLastUsedAt());
    }

    @Test
    void listDatabases_nullEffective_whenBothNull() {
        stubDatabaseQueries(List.of("db1"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertNull(result.getFirst().effectiveLastUsedAt());
    }

    // ---- cache ----

    @Test
    void listDatabases_usesCacheOnSecondCall() {
        stubDatabaseQueries(List.of("db1"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        useCase.listDatabases(REPO);
        useCase.listDatabases(REPO);

        // DB should only be queried once
        verify(databaseService, times(1)).listDatabases(REPO);
    }

    @Test
    void invalidateCache_causesRefreshOnNextCall() {
        stubDatabaseQueries(List.of("db1"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        useCase.listDatabases(REPO);
        useCase.invalidateCache(REPO);
        useCase.listDatabases(REPO);

        verify(databaseService, times(2)).listDatabases(REPO);
    }

    @Test
    void listDatabases_returnsStaleCache_onError() {
        // Use a very short TTL so cache expires immediately
        setField("cacheTtlSeconds", 0);

        stubDatabaseQueries(List.of("db1"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        // First call succeeds and caches
        List<ManagedDatabaseInfo> first = useCase.listDatabases(REPO);
        assertEquals(1, first.size());

        // Make next fetch fail — cache is expired (TTL=0) but entry still exists
        when(databaseService.listDatabases(REPO)).thenThrow(new RuntimeException("connection failed"));

        // Should return stale data instead of throwing
        List<ManagedDatabaseInfo> second = useCase.listDatabases(REPO);
        assertEquals(1, second.size());
    }

    @Test
    void listDatabases_throwsOnError_whenNoCacheExists() {
        when(databaseService.listDatabases(REPO)).thenThrow(new RuntimeException("connection failed"));

        assertThrows(RuntimeException.class, () -> useCase.listDatabases(REPO));
    }

    @Test
    void listDatabases_cacheReturnsImmutableList() {
        stubDatabaseQueries(List.of("db1"));
        when(managedDatabaseRepository.findByRepository(REPO)).thenReturn(List.of());

        List<ManagedDatabaseInfo> result = useCase.listDatabases(REPO);

        assertThrows(UnsupportedOperationException.class, () -> result.add(null));
    }

    // ---- helpers ----

    private void stubDatabaseQueries(List<String> dbNames) {
        when(databaseService.listDatabases(REPO)).thenReturn(dbNames);
        when(databaseService.getDatabaseSizes(REPO)).thenReturn(
                dbNames.stream().collect(java.util.stream.Collectors.toMap(n -> n, n -> 1024L)));
        when(databaseService.getActiveConnectionCounts(REPO)).thenReturn(
                dbNames.stream().collect(java.util.stream.Collectors.toMap(n -> n, n -> 0)));
        when(databaseService.getLastActivityTimes(REPO)).thenReturn(Map.of());
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = ListManagedDatabasesUseCase.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(useCase, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
