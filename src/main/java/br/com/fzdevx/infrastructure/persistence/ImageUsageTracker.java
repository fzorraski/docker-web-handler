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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks when each Docker image was last seen in use by a container.
 * Usage tracking is a passive side channel of the image flows - failures are
 * logged, never propagated, so an app-DB blip cannot break image listing.
 * Postgres backend: one row per image, batch-upserted.
 * File backend (legacy): JSON map { "sha256:abc...": "2024-03-18T12:00:00Z" }.
 */
@ApplicationScoped
public class ImageUsageTracker {

    private static final Logger LOG = Logger.getLogger(ImageUsageTracker.class);

    @ConfigProperty(name = "image.usage.file", defaultValue = "data/image-usage.json")
    String filePath;

    @Inject
    PersistenceBackendProducer backendProducer;

    @Inject
    JdbcSupport jdbc;

    private final ConcurrentHashMap<String, String> usageMap = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    private boolean postgres() {
        return backendProducer.isPostgres();
    }

    @PostConstruct
    void init() {
        if (!postgres()) {
            load();
        }
    }

    /**
     * Update timestamps for all images currently in use.
     * Images no longer tracked are left as-is (preserving their last-used time).
     */
    public void markInUse(Set<String> imageIds) {
        if (imageIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        if (postgres()) {
            try {
                // one set-based statement instead of a round trip per image
                jdbc.inTransaction(connection -> {
                    java.sql.Array ids = connection.createArrayOf("text", imageIds.toArray());
                    jdbc.update(connection, """
                            INSERT INTO image_usage (image_id, last_used_at)
                            SELECT unnest(?), ?
                            ON CONFLICT (image_id) DO UPDATE SET last_used_at = EXCLUDED.last_used_at
                            """, ids, now);
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warn("Failed to persist image usage data: " + e.getMessage());
            }
            return;
        }
        String nowText = now.toString();
        lock.writeLock().lock();
        try {
            for (String id : imageIds) {
                usageMap.put(id, nowText);
            }
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the last-used timestamp for an image, or empty if never tracked.
     */
    public Optional<Instant> getLastUsed(String imageId) {
        if (postgres()) {
            try {
                return jdbc.queryOne("SELECT last_used_at FROM image_usage WHERE image_id = ?",
                        rs -> JdbcSupport.instant(rs, "last_used_at"), imageId);
            } catch (RuntimeException e) {
                LOG.warn("Failed to read image usage data: " + e.getMessage());
                return Optional.empty();
            }
        }
        lock.readLock().lock();
        try {
            String ts = usageMap.get(imageId);
            return ts != null ? Optional.of(Instant.parse(ts)) : Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * All last-used timestamps in one read - use this instead of calling
     * {@link #getLastUsed} inside a loop over images.
     *
     * THROWS on a store failure (postgres backend): callers that merely
     * display the data may catch and degrade, but destructive callers
     * (image prune) must abort rather than treat an outage as "no image
     * was ever used" and delete recently-used images.
     */
    public Map<String, Instant> getAllLastUsed() {
        if (postgres()) {
            Map<String, Instant> result = new HashMap<>();
            jdbc.query("SELECT image_id, last_used_at FROM image_usage",
                            rs -> Map.entry(rs.getString(1), JdbcSupport.instant(rs, "last_used_at")))
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        }
        lock.readLock().lock();
        try {
            Map<String, Instant> result = new HashMap<>();
            usageMap.forEach((id, ts) -> {
                try {
                    result.put(id, Instant.parse(ts));
                } catch (Exception ignored) {
                }
            });
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Remove tracking entries for image IDs that no longer exist.
     */
    public void cleanup(Set<String> existingImageIds) {
        if (postgres()) {
            try {
                jdbc.inTransaction(connection -> {
                    java.sql.Array existing = connection.createArrayOf("text", existingImageIds.toArray());
                    jdbc.update(connection, "DELETE FROM image_usage WHERE image_id <> ALL (?)", existing);
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warn("Failed to clean up image usage data: " + e.getMessage());
            }
            return;
        }
        lock.writeLock().lock();
        try {
            usageMap.keySet().retainAll(existingImageIds);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) return;
        try {
            String content = Files.readString(path);
            if (content.isBlank()) return;
            Map<String, String> loaded = jsonb.fromJson(content, Map.class);
            if (loaded != null) usageMap.putAll(loaded);
        } catch (Exception e) {
            LOG.warn("Failed to load image usage data: " + e.getMessage());
        }
    }

    private void persist() {
        Path path = Path.of(filePath);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, jsonb.toJson(usageMap));
        } catch (IOException e) {
            LOG.error("Failed to persist image usage data: " + e.getMessage());
        }
    }
}
