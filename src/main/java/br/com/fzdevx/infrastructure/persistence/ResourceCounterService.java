package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.infrastructure.persistence.jdbc.JdbcSupport;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent lifetime counters for managed resources.
 * Counts only go up -- they represent "total ever managed", not current state.
 * Postgres backend: one row per counter, incremented atomically.
 * File backend (legacy): JSON map { "containers": 42, ... } rewritten per increment.
 */
@ApplicationScoped
public class ResourceCounterService {

    private static final Logger LOG = Logger.getLogger(ResourceCounterService.class);

    @ConfigProperty(name = "resource.counters.file", defaultValue = "data/resource-counters.json")
    String filePath;

    @Inject
    PersistenceBackendProducer backendProducer;

    @Inject
    JdbcSupport jdbc;

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    public static final String CONTAINERS = "containers";
    public static final String IMAGES_DELETED = "imagesDeleted";
    public static final String DUMPS = "dumps";
    public static final String SNAPSHOTS = "snapshots";
    public static final String RESTORES = "restores";
    public static final String SCHEDULES_EXECUTED = "schedulesExecuted";
    public static final String LOGS_ANALYZED = "logsAnalyzed";
    public static final String DATABASES_DELETED = "databasesDeleted";
    public static final String MIGRATIONS_EXECUTED = "migrationsExecuted";
    private static final String STARTED_AT_KEY = "_startedAt";

    private static final java.util.List<String> COUNTER_KEYS = java.util.List.of(
            CONTAINERS, IMAGES_DELETED, DUMPS, SNAPSHOTS, RESTORES,
            SCHEDULES_EXECUTED, LOGS_ANALYZED, DATABASES_DELETED, MIGRATIONS_EXECUTED);

    private volatile String startedAt;

    private boolean postgres() {
        return backendProducer.isPostgres();
    }

    @PostConstruct
    void init() {
        if (postgres()) {
            // must never fail bean creation: counters are a side channel, and
            // the database may still be coming up - startedAt is retried lazily
            try {
                initPostgres();
            } catch (RuntimeException e) {
                LOG.warn("Resource counters unavailable at startup (will retry lazily): " + e.getMessage());
            }
            return;
        }
        load();
        // Set startedAt once on first-ever boot, persist it so it survives restarts
        if (startedAt == null) {
            startedAt = Instant.now().toString();
            persist();
        }
    }

    /** startedAt is stored as epoch millis in its own counter row. */
    private void initPostgres() {
        jdbc.update("""
                INSERT INTO resource_counter (counter_key, counter_value) VALUES (?, ?)
                ON CONFLICT (counter_key) DO NOTHING
                """, STARTED_AT_KEY, System.currentTimeMillis());
        startedAt = jdbc.queryOne(
                        "SELECT counter_value FROM resource_counter WHERE counter_key = ?",
                        rs -> rs.getLong(1), STARTED_AT_KEY)
                .map(millis -> Instant.ofEpochMilli(millis).toString())
                .orElse(null);
    }

    public String getStartedAt() {
        if (startedAt == null && postgres()) {
            try {
                initPostgres();
            } catch (RuntimeException e) {
                LOG.warn("Failed to load startedAt counter: " + e.getMessage());
            }
        }
        return startedAt;
    }

    /**
     * Counters are lifetime statistics - a failed write must never fail the
     * caller's primary operation (matching the file backend, which only ever
     * logged IO errors).
     */
    public void increment(String key) {
        if (postgres()) {
            try {
                jdbc.update("""
                        INSERT INTO resource_counter (counter_key, counter_value) VALUES (?, 1)
                        ON CONFLICT (counter_key) DO UPDATE
                        SET counter_value = resource_counter.counter_value + 1
                        """, key);
            } catch (RuntimeException e) {
                LOG.warn("Failed to increment counter '" + key + "': " + e.getMessage());
            }
            return;
        }
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        persist();
    }

    public long get(String key) {
        if (postgres()) {
            return jdbc.queryOne("SELECT counter_value FROM resource_counter WHERE counter_key = ?",
                    rs -> rs.getLong(1), key).orElse(0L);
        }
        AtomicLong val = counters.get(key);
        return val != null ? val.get() : 0;
    }

    public Map<String, Long> getAll() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        COUNTER_KEYS.forEach(key -> result.put(key, 0L));
        if (postgres()) {
            jdbc.query("SELECT counter_key, counter_value FROM resource_counter",
                            rs -> Map.entry(rs.getString(1), rs.getLong(2)))
                    .forEach(entry -> {
                        if (result.containsKey(entry.getKey())) {
                            result.put(entry.getKey(), entry.getValue());
                        }
                    });
            return result;
        }
        result.replaceAll((key, zero) -> get(key));
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
                    if (STARTED_AT_KEY.equals(k)) {
                        startedAt = String.valueOf(v);
                    } else {
                        long val = v instanceof Number ? ((Number) v).longValue() : 0;
                        counters.put(k, new AtomicLong(val));
                    }
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
            Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            counters.forEach((k, v) -> snapshot.put(k, v.get()));
            if (startedAt != null) snapshot.put(STARTED_AT_KEY, startedAt);
            Files.writeString(path, jsonb.toJson(snapshot));
        } catch (IOException e) {
            LOG.error("Failed to persist resource counters: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
}
