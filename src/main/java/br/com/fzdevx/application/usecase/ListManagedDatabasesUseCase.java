package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;


@ApplicationScoped
public class ListManagedDatabasesUseCase {

    @Inject
    DatabaseService databaseService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    @ConfigProperty(name = "database.managed.cache.ttl-seconds", defaultValue = "15")
    int cacheTtlSeconds;

    private record CachedResult(List<ManagedDatabaseInfo> data, Instant fetchedAt) {}

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    public List<String> getRepositories() {
        return allowedRepositoryResolver.getAllowed().stream()
                .filter(databaseService::hasDatabaseConfig)
                .toList();
    }

    public List<ManagedDatabaseInfo> listDatabases(String repository) {
        CachedResult cached = cache.get(repository);
        if (cached != null && cached.fetchedAt.plusSeconds(cacheTtlSeconds).isAfter(Instant.now())) {
            return cached.data;
        }

        // Fetch outside lock — concurrent threads may both fetch, which is acceptable
        // (idempotent, and avoids blocking all threads during slow PG queries)
        try {
            List<ManagedDatabaseInfo> data = List.copyOf(fetchFromDatabase(repository));
            cache.put(repository, new CachedResult(data, Instant.now()));
            Log.debugf("Managed databases cache refreshed for repository '%s' (%d databases).", repository, data.size());
            return data;
        } catch (Exception e) {
            // If fetch fails but stale cache exists, serve stale data instead of failing
            if (cached != null) {
                Log.warnf("Failed to refresh cache for '%s', serving stale data: %s", repository, e.getMessage());
                return cached.data;
            }
            throw e;
        }
    }

    public void invalidateCache(String repository) {
        cache.remove(repository);
    }

    public void invalidateAllCaches() {
        cache.clear();
    }

    private List<ManagedDatabaseInfo> fetchFromDatabase(String repository) {
        List<String> dbNames = databaseService.listDatabases(repository);
        Map<String, Long> sizes = databaseService.getDatabaseSizes(repository);
        Map<String, Integer> connections = databaseService.getActiveConnectionCounts(repository);
        Map<String, Instant> pgActivity = databaseService.getLastActivityTimes(repository);

        Map<String, ManagedDatabase> persisted = managedDatabaseRepository.findByRepository(repository)
                .stream()
                .collect(Collectors.toMap(ManagedDatabase::getName, Function.identity()));

        // Fetch all expirations once and group by database name (avoids N+1)
        Map<String, List<ContainerExpiration>> expirationsByDb = expirationService.findAll().stream()
                .filter(e -> e.getDatabaseName() != null)
                .collect(Collectors.groupingBy(ContainerExpiration::getDatabaseName));

        List<ManagedDatabaseInfo> result = new ArrayList<>();

        for (String name : dbNames) {
            ManagedDatabase md = persisted.get(name);
            if (md == null) {
                md = new ManagedDatabase(repository, name);
                managedDatabaseRepository.save(md);
            }

            Instant pgLast = pgActivity.get(name);
            Instant appLast = md.getAppLastUsedAt();
            Instant effective = latest(pgLast, appLast);

            // Container association
            List<ContainerExpiration> expirations = expirationsByDb.getOrDefault(name, List.of());
            int containerCount = expirations.size();
            Instant earliestExp = expirations.stream()
                    .map(ContainerExpiration::getExpiresAt)
                    .filter(e -> e != null)
                    .min(Instant::compareTo).orElse(null);
            boolean scheduledForDeletion = expirations.stream()
                    .anyMatch(ContainerExpiration::isDeleteDatabaseOnExpiration);

            result.add(new ManagedDatabaseInfo(
                    name,
                    repository,
                    sizes.getOrDefault(name, 0L),
                    connections.getOrDefault(name, 0),
                    pgLast,
                    appLast,
                    effective,
                    md.isProtectedFlag(),
                    md.getCreatedAt(),
                    md.getDescription(),
                    containerCount,
                    earliestExp,
                    scheduledForDeletion,
                    md.getLastRestoredFrom(),
                    md.getLastRestoredAt(),
                    md.getCreatedBy()
            ));
        }

        return result;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
