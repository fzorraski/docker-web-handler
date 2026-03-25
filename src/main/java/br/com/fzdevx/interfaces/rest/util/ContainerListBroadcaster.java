package br.com.fzdevx.interfaces.rest.util;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.concurrent.*;

/**
 * Broadcasts "refresh" events to all connected SSE clients when the container list changes.
 * Debounces rapid mutations (e.g. bulk operations) into a single broadcast.
 */
@ApplicationScoped
public class ContainerListBroadcaster {

    private final ConcurrentHashMap<SseEventSink, Sse> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> pendingBroadcast;

    public void register(SseEventSink sink, Sse sse) {
        clients.put(sink, sse);
        Log.debugf("Container list subscriber registered. Active: %d", clients.size());
    }

    /**
     * Signal that the container list has changed. Debounced: rapid calls within 500ms
     * are coalesced into a single broadcast, avoiding floods during bulk operations.
     */
    public void notifyChange() {
        ScheduledFuture<?> existing = pendingBroadcast;
        if (existing != null) existing.cancel(false);
        pendingBroadcast = debouncer.schedule(this::broadcast, 500, TimeUnit.MILLISECONDS);
    }

    private void broadcast() {
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
                        .name("refresh")
                        .data(String.class, "container-list-changed")
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .build());
            } catch (IllegalStateException e) {
                it.remove();
            }
        }
        Log.debugf("Container list broadcast sent to %d client(s).", clients.size());
    }

    void onShutdown(@Observes ShutdownEvent event) {
        debouncer.shutdownNow();
        clients.keySet().forEach(sink -> {
            try { sink.close(); } catch (Exception ignored) {}
        });
        clients.clear();
    }
}
