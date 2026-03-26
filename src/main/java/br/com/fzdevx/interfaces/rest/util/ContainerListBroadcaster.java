package br.com.fzdevx.interfaces.rest.util;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

/**
 * Broadcasts SSE events to all connected clients for real-time container list synchronization.
 * Supports locking (block rows during bulk operations), unlocking (release rows),
 * and refresh (container list changed) events.
 * Locks have a TTL: a background task evicts stale locks after 30 seconds,
 * protecting against orphaned locks when a client crashes mid-operation.
 */
@ApplicationScoped
public class ContainerListBroadcaster {

    private static final long LOCK_TTL_SECONDS = 30;

    private final ConcurrentHashMap<SseEventSink, Sse> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lockedIds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> pendingBroadcast;

    void onStartup(@Observes StartupEvent event) {
        executor.scheduleAtFixedRate(this::evictStaleLocks, LOCK_TTL_SECONDS, 10, TimeUnit.SECONDS);
    }

    public void register(SseEventSink sink, Sse sse) {
        clients.put(sink, sse);
        if (!lockedIds.isEmpty()) {
            try {
                sink.send(sse.newEventBuilder()
                        .name("locking")
                        .data(String.class, toJsonArray(List.copyOf(lockedIds.keySet())))
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .build());
            } catch (IllegalStateException ignored) {}
        }
        Log.debugf("Container list subscriber registered. Active: %d", clients.size());
    }

    /**
     * Broadcast a "locking" event with container IDs that are about to be operated on.
     * Sent immediately (no debounce) so other clients can block those rows.
     */
    public void broadcastLocking(List<String> containerIds) {
        Instant now = Instant.now();
        containerIds.forEach(id -> lockedIds.put(id, now));
        sendToAll("locking", toJsonArray(containerIds));
    }

    /**
     * Broadcast an "unlocking" event with container IDs that finished processing.
     * Other clients remove those specific IDs from their lock state.
     */
    public void broadcastUnlocking(List<String> containerIds) {
        containerIds.forEach(lockedIds::remove);
        sendToAll("unlocking", toJsonArray(containerIds));
    }

    /**
     * Signal that the container list has changed. Debounced: rapid calls within 500ms
     * are coalesced into a single broadcast, avoiding floods during bulk operations.
     */
    public void notifyChange() {
        ScheduledFuture<?> existing = pendingBroadcast;
        if (existing != null) existing.cancel(false);
        pendingBroadcast = executor.schedule(this::broadcast, 500, TimeUnit.MILLISECONDS);
    }

    private void evictStaleLocks() {
        Instant cutoff = Instant.now().minusSeconds(LOCK_TTL_SECONDS);
        List<String> stale = lockedIds.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .map(ConcurrentHashMap.Entry::getKey)
                .toList();
        if (!stale.isEmpty()) {
            stale.forEach(lockedIds::remove);
            sendToAll("unlocking", toJsonArray(stale));
            Log.infof("Evicted %d stale lock(s): %s", stale.size(), stale);
        }
    }

    private void broadcast() {
        sendToAll("refresh", "container-list-changed");
    }

    private String toJsonArray(List<String> ids) {
        return "[" + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()) + "]";
    }

    private void sendToAll(String eventName, String data) {
        var it = clients.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            SseEventSink sink = entry.getKey();
            Sse sse = entry.getValue();
            if (sink.isClosed()) {
                it.remove();
                continue;
            }
            try {
                sink.send(sse.newEventBuilder()
                        .name(eventName)
                        .data(String.class, data)
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .build());
            } catch (IllegalStateException e) {
                it.remove();
            }
        }
        Log.debugf("SSE broadcast '%s' sent to %d client(s).", eventName, clients.size());
    }

    void onShutdown(@Observes ShutdownEvent event) {
        executor.shutdownNow();
        clients.keySet().forEach(sink -> {
            try { sink.close(); } catch (Exception ignored) {}
        });
        clients.clear();
        lockedIds.clear();
    }
}
