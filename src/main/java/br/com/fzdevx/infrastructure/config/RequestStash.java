package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.dto.CreateSnapshotRequest;
import br.com.fzdevx.application.dto.PruneImagesRequest;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.dto.RunMigrationRequest;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RequestStash {

    private static final long TTL_MINUTES = 5;

    private record StashedEntry<T>(T request, Instant createdAt) {}

    private final ConcurrentHashMap<String, StashedEntry<RunContainerRequest>> stash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<RestoreDumpRequest>> restoreStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<CreateSnapshotRequest>> snapshotStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<PruneImagesRequest>> pruneStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<RunMigrationRequest>> migrationStash = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleanupScheduler;

    @PostConstruct
    void init() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "request-stash-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::evictExpired, TTL_MINUTES, TTL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(TTL_MINUTES * 60);
        int evicted = evictMap(stash, cutoff)
                + evictMap(restoreStash, cutoff)
                + evictMap(snapshotStash, cutoff)
                + evictMap(pruneStash, cutoff)
                + evictMap(migrationStash, cutoff)
                + evictMap(terminalStash, cutoff);
        if (evicted > 0) {
            Log.infof("RequestStash: evicted %d expired ticket(s).", evicted);
        }
    }

    private <T> int evictMap(ConcurrentHashMap<String, StashedEntry<T>> map, Instant cutoff) {
        int count = 0;
        for (Map.Entry<String, StashedEntry<T>> entry : map.entrySet()) {
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                map.remove(entry.getKey());
                count++;
            }
        }
        return count;
    }

    private <T> String put(ConcurrentHashMap<String, StashedEntry<T>> map, T request) {
        String ticket = UUID.randomUUID().toString();
        map.put(ticket, new StashedEntry<>(request, Instant.now()));
        return ticket;
    }

    private <T> T take(ConcurrentHashMap<String, StashedEntry<T>> map, String ticket) {
        StashedEntry<T> entry = map.remove(ticket);
        return entry != null ? entry.request() : null;
    }

    public String stash(RunContainerRequest request) {
        return put(stash, request);
    }

    public RunContainerRequest retrieve(String ticket) {
        return take(stash, ticket);
    }

    public String stashRestore(RestoreDumpRequest request) {
        return put(restoreStash, request);
    }

    public RestoreDumpRequest retrieveRestore(String ticket) {
        return take(restoreStash, ticket);
    }

    public String stashSnapshot(CreateSnapshotRequest request) {
        return put(snapshotStash, request);
    }

    public CreateSnapshotRequest retrieveSnapshot(String ticket) {
        return take(snapshotStash, ticket);
    }

    public String stashPrune(PruneImagesRequest request) {
        return put(pruneStash, request);
    }

    public PruneImagesRequest retrievePrune(String ticket) {
        return take(pruneStash, ticket);
    }

    public String stashMigration(RunMigrationRequest request) {
        return put(migrationStash, request);
    }

    public RunMigrationRequest retrieveMigration(String ticket) {
        return take(migrationStash, ticket);
    }

    // Terminal tickets store the containerId that was authorized
    private final ConcurrentHashMap<String, StashedEntry<String>> terminalStash = new ConcurrentHashMap<>();

    public String stashTerminal(String containerId) {
        return put(terminalStash, containerId);
    }

    public String retrieveTerminal(String ticket) {
        return take(terminalStash, ticket);
    }
}
