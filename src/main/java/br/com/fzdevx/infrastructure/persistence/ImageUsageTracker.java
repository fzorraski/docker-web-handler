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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks when each Docker image was last seen in use by a container.
 * Persisted as a JSON map: { "sha256:abc...": "2024-03-18T12:00:00Z", ... }
 */
@ApplicationScoped
public class ImageUsageTracker {

    private static final Logger LOG = Logger.getLogger(ImageUsageTracker.class);

    @ConfigProperty(name = "image.usage.file", defaultValue = "data/image-usage.json")
    String filePath;

    private final ConcurrentHashMap<String, String> usageMap = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    @PostConstruct
    void init() {
        load();
    }

    /**
     * Update timestamps for all images currently in use.
     * Images no longer tracked are left as-is (preserving their last-used time).
     */
    public void markInUse(Set<String> imageIds) {
        String now = Instant.now().toString();
        lock.writeLock().lock();
        try {
            for (String id : imageIds) {
                usageMap.put(id, now);
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
        lock.readLock().lock();
        try {
            String ts = usageMap.get(imageId);
            return ts != null ? Optional.of(Instant.parse(ts)) : Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Remove tracking entries for image IDs that no longer exist.
     */
    public void cleanup(Set<String> existingImageIds) {
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
