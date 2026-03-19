package br.com.fzdevx.infrastructure.persistence;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent lifetime counters for managed resources.
 * Counts only go up -- they represent "total ever managed", not current state.
 * Persisted as JSON: { "containers": 42, "images": 15, ... }
 */
@ApplicationScoped
public class ResourceCounterService {

    private static final Logger LOG = Logger.getLogger(ResourceCounterService.class);

    @ConfigProperty(name = "resource.counters.file", defaultValue = "data/resource-counters.json")
    String filePath;

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    public static final String CONTAINERS = "containers";
    public static final String IMAGES_DELETED = "imagesDeleted";
    public static final String DUMPS = "dumps";
    public static final String SNAPSHOTS = "snapshots";
    public static final String RESTORES = "restores";

    @PostConstruct
    void init() {
        load();
    }

    public void increment(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        persist();
    }

    public long get(String key) {
        AtomicLong val = counters.get(key);
        return val != null ? val.get() : 0;
    }

    public Map<String, Long> getAll() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put(CONTAINERS, get(CONTAINERS));
        result.put(IMAGES_DELETED, get(IMAGES_DELETED));
        result.put(DUMPS, get(DUMPS));
        result.put(SNAPSHOTS, get(SNAPSHOTS));
        result.put(RESTORES, get(RESTORES));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) return;
        lock.writeLock().lock();
        try {
            String content = Files.readString(path);
            if (content.isBlank()) return;
            Map<String, Object> loaded = jsonb.fromJson(content, Map.class);
            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    long val = v instanceof Number ? ((Number) v).longValue() : 0;
                    counters.put(k, new AtomicLong(val));
                });
            }
        } catch (Exception e) {
            LOG.warn("Failed to load resource counters: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void persist() {
        Path path = Path.of(filePath);
        lock.readLock().lock();
        try {
            Files.createDirectories(path.getParent());
            Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
            counters.forEach((k, v) -> snapshot.put(k, v.get()));
            Files.writeString(path, jsonb.toJson(snapshot));
        } catch (IOException e) {
            LOG.error("Failed to persist resource counters: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
}
