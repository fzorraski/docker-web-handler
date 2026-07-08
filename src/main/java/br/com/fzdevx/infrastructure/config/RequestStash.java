package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.dto.CreateSnapshotRequest;
import br.com.fzdevx.application.dto.PruneImagesRequest;
import br.com.fzdevx.application.dto.RemoveContainerRequest;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.application.dto.UpgradeContainerRequest;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    private record StashedEntry<T>(T request, Instant createdAt, String userId) {}

    @Inject
    CurrentUser currentUser;

    private final ConcurrentHashMap<String, StashedEntry<RunContainerRequest>> stash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<RestoreDumpRequest>> restoreStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<CreateSnapshotRequest>> snapshotStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<PruneImagesRequest>> pruneStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<RunMigrationRequest>> migrationStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<UpgradeContainerRequest>> upgradeStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<AnalyzeLogFileRequest>> logAnalysisStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<RemoveContainerRequest>> removeStash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StashedEntry<ComposeRequest>> composeStash = new ConcurrentHashMap<>();

    public record ComposeRequest(java.util.List<String> ids, String presetName, int slowThresholdMs, AnalysisOptions options) {}

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
                + evictMap(upgradeStash, cutoff)
                + evictMap(terminalStash, cutoff)
                + evictLogAnalysisStash(cutoff)
                + evictMap(removeStash, cutoff)
                + evictMap(composeStash, cutoff);
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
        map.put(ticket, new StashedEntry<>(request, Instant.now(), safeCurrentUserId()));
        return ticket;
    }

    private <T> T take(ConcurrentHashMap<String, StashedEntry<T>> map, String ticket) {
        StashedEntry<T> entry = map.get(ticket);
        if (entry == null) {
            return null;
        }
        // Under RBAC a ticket may only be redeemed by the user who created it.
        // Checked before removal so a foreign redemption attempt does not
        // consume (and thereby invalidate) the rightful owner's ticket.
        if (entry.userId() != null && !entry.userId().equals(safeCurrentUserId())) {
            Log.warnf("RequestStash: ticket redeemed by a different user - rejecting.");
            return null;
        }
        // conditional remove keeps single-use semantics under concurrent redemption
        return map.remove(ticket, entry) ? entry.request() : null;
    }

    /**
     * The id of the RBAC user bound to the current request, or null when RBAC
     * is inactive or no request scope is active (e.g. WebSocket handshake).
     */
    private String safeCurrentUserId() {
        try {
            return currentUser.isRbacActive() ? currentUser.getUserId() : null;
        } catch (RuntimeException e) {
            return null;
        }
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

    public String stashUpgrade(UpgradeContainerRequest request) {
        return put(upgradeStash, request);
    }

    public UpgradeContainerRequest retrieveUpgrade(String ticket) {
        return take(upgradeStash, ticket);
    }

    public String stashRemove(RemoveContainerRequest request) {
        return put(removeStash, request);
    }

    public RemoveContainerRequest retrieveRemove(String ticket) {
        return take(removeStash, ticket);
    }

    // Terminal tickets store the containerId that was authorized plus the
    // authorizing user. The WebSocket endpoint redeems them outside any REST
    // request scope, so the user match happens there (against the handshake's
    // session user), not in take().
    public record TerminalGrant(String containerId, String userId) {}

    private final ConcurrentHashMap<String, StashedEntry<TerminalGrant>> terminalStash = new ConcurrentHashMap<>();

    public String stashTerminal(String containerId) {
        String ticket = UUID.randomUUID().toString();
        terminalStash.put(ticket, new StashedEntry<>(
                new TerminalGrant(containerId, safeCurrentUserId()), Instant.now(), null));
        return ticket;
    }

    public TerminalGrant retrieveTerminal(String ticket) {
        StashedEntry<TerminalGrant> entry = terminalStash.remove(ticket);
        return entry != null ? entry.request() : null;
    }

    public String stashLogAnalysis(AnalyzeLogFileRequest request) {
        return put(logAnalysisStash, request);
    }

    public AnalyzeLogFileRequest retrieveLogAnalysis(String ticket) {
        return take(logAnalysisStash, ticket);
    }

    public String stashCompose(ComposeRequest request) {
        return put(composeStash, request);
    }

    public ComposeRequest retrieveCompose(String ticket) {
        return take(composeStash, ticket);
    }

    private int evictLogAnalysisStash(Instant cutoff) {
        int count = 0;
        for (Map.Entry<String, StashedEntry<AnalyzeLogFileRequest>> entry : logAnalysisStash.entrySet()) {
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                StashedEntry<AnalyzeLogFileRequest> removed = logAnalysisStash.remove(entry.getKey());
                if (removed != null) {
                    removed.request().cleanupTempFiles();
                    count++;
                }
            }
        }
        return count;
    }
}
