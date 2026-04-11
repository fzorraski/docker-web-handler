package br.com.fzdevx.interfaces.rest.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class LogAnalysisBroadcaster {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long VIEWER_TTL_SECONDS = 120;

    private final ConcurrentHashMap<SseEventSink, Sse> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ViewerEntry> viewerMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> activeAnalyses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> activeFilenames = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "log-analysis-viewer-cleanup");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> pendingViewerBroadcast;

    private record ViewerEntry(String analysisId, Instant lastSeen) {}

    void onInit(@Observes StartupEvent event) {
        executor.scheduleAtFixedRate(this::cleanup, 10, 10, TimeUnit.SECONDS);
    }

    public void register(SseEventSink sink, Sse sse) {
        clients.put(sink, sse);
        // Send current active analyses so late joiners see in-progress banners
        for (var entry : activeAnalyses.entrySet()) {
            if (entry.getValue().get() <= 0) continue;
            try {
                sink.send(sse.newEventBuilder()
                        .name("analysis-started")
                        .data(String.class, toJson(Map.of("user", "", "filenames", entry.getKey())))
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .build());
            } catch (IllegalStateException ignored) { break; }
        }
        Map<String, Long> counts = getViewerCounts();
        if (!counts.isEmpty()) {
            try {
                sink.send(sse.newEventBuilder()
                        .name("viewers")
                        .data(String.class, toJson(counts))
                        .mediaType(MediaType.TEXT_PLAIN_TYPE)
                        .build());
            } catch (IllegalStateException ignored) {}
        }
    }

    public void setViewing(String clientToken, String analysisId) {
        if (analysisId == null || analysisId.isBlank()) {
            viewerMap.remove(clientToken);
        } else {
            viewerMap.put(clientToken, new ViewerEntry(analysisId, Instant.now()));
        }
        scheduleViewerBroadcast();
    }

    public Set<String> getActiveFilenames() {
        var result = new java.util.HashSet<String>();
        activeFilenames.forEach((name, count) -> {
            if (count.get() > 0) result.add(name);
        });
        return result;
    }

    public Map<String, Integer> getActiveAnalyses() {
        var result = new java.util.HashMap<String, Integer>();
        activeAnalyses.forEach((k, v) -> {
            int count = v.get();
            if (count > 0) result.put(k, count);
        });
        return Map.copyOf(result);
    }

    public Map<String, Long> getViewerCounts() {
        return viewerMap.values().stream()
                .collect(Collectors.groupingBy(ViewerEntry::analysisId, Collectors.counting()));
    }

    public void broadcastStarted(String user, String filenames, java.util.List<String> individualFilenames) {
        activeAnalyses.computeIfAbsent(filenames, _ -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
        for (String name : individualFilenames) {
            activeFilenames.computeIfAbsent(name, _ -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
        }
        sendToAll("analysis-started", toJson(Map.of("user", user, "filenames", filenames)));
    }

    public void broadcastCompleted(String user, String filenames, String analysisId,
                                   java.util.List<String> individualFilenames) {
        activeAnalyses.computeIfPresent(filenames, (_, count) -> count.decrementAndGet() <= 0 ? null : count);
        for (String name : individualFilenames) {
            activeFilenames.computeIfPresent(name, (_, count) -> count.decrementAndGet() <= 0 ? null : count);
        }
        sendToAll("analysis-completed",
                toJson(Map.of("user", user, "filenames", filenames, "analysisId", analysisId)));
    }

    public void broadcastDeleted(String analysisId) {
        viewerMap.values().removeIf(e -> e.analysisId().equals(analysisId));
        sendToAll("analysis-deleted", toJson(Map.of("analysisId", analysisId)));
        scheduleViewerBroadcast();
    }

    private void scheduleViewerBroadcast() {
        ScheduledFuture<?> existing = pendingViewerBroadcast;
        if (existing != null) existing.cancel(false);
        pendingViewerBroadcast = executor.schedule(
                () -> sendToAll("viewers", toJson(getViewerCounts())),
                500, TimeUnit.MILLISECONDS);
    }

    private void cleanup() {
        boolean removedClients = clients.entrySet().removeIf(e -> e.getKey().isClosed());

        Instant cutoff = Instant.now().minusSeconds(VIEWER_TTL_SECONDS);
        boolean removedViewers = viewerMap.entrySet().removeIf(e -> e.getValue().lastSeen().isBefore(cutoff));

        if (removedClients || removedViewers) {
            scheduleViewerBroadcast();
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
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
    }

    void onShutdown(@Observes ShutdownEvent event) {
        executor.shutdownNow();
        clients.keySet().forEach(sink -> {
            try { sink.close(); } catch (Exception ignored) {}
        });
        clients.clear();
        viewerMap.clear();
        activeAnalyses.clear();
        activeFilenames.clear();
    }
}
