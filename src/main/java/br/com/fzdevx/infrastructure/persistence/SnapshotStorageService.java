package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.application.port.SnapshotRepository;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class SnapshotStorageService {

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledExpirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Inject
    SnapshotRepository snapshotRepository;

    @Inject
    @ConfigProperty(name = "database.snapshot.storage.dir", defaultValue = "data/snapshots/")
    String storageDir;

    @Inject
    @ConfigProperty(name = "database.snapshot.max-size-mb", defaultValue = "1500")
    int maxSizeMb;

    void onStartup(@Observes StartupEvent event) {
        List<DatabaseSnapshot> snapshots = snapshotRepository.findAll();
        if (snapshots.isEmpty()) return;
        Log.infof("Reloading %d snapshot expirations.", snapshots.size());
        for (DatabaseSnapshot snapshot : snapshots) {
            if (snapshot.getExpiresAt() == null) continue;
            if (snapshot.isExpired()) {
                Log.infof("Snapshot '%s' is past expiration, deleting now.", snapshot.getId());
                deleteSnapshot(snapshot.getId());
            } else {
                scheduleExpiration(snapshot);
            }
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        scheduler.shutdownNow();
    }

    public int getMaxSizeMb() {
        return maxSizeMb;
    }

    public boolean isStorageFull() {
        return getTotalStorageBytes() >= (long) maxSizeMb * 1024 * 1024;
    }

    public StoreResult storeFromStream(InputStream pgDumpOutput, DatabaseSnapshot snapshot) throws IOException {
        Path dir = Path.of(storageDir);
        Files.createDirectories(dir);

        Path storedPath = dir.resolve(snapshot.getStoredFilename());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }

        long bytesWritten;
        try (OutputStream fos = Files.newOutputStream(storedPath);
             DigestOutputStream digestOut = new DigestOutputStream(fos, digest);
             GZIPOutputStream gzos = new GZIPOutputStream(digestOut)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = pgDumpOutput.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
        }
        bytesWritten = Files.size(storedPath);

        String hash = HexFormat.of().formatHex(digest.digest());
        return new StoreResult(bytesWritten, hash);
    }

    public void saveMetadata(DatabaseSnapshot snapshot) {
        snapshotRepository.save(snapshot);
        if (snapshot.getExpiresAt() != null) {
            scheduleExpiration(snapshot);
        }
        Log.infof("Saved snapshot '%s' for database '%s' (%d bytes, hash: %s)",
                snapshot.getId(), snapshot.getSourceDatabaseName(),
                snapshot.getFileSize(), snapshot.getMd5Hash());
    }

    public boolean updateMetadata(String id, String label, String description) {
        Optional<DatabaseSnapshot> opt = snapshotRepository.findById(id);
        if (opt.isEmpty()) return false;

        DatabaseSnapshot snapshot = opt.get();
        snapshot.setLabel(label);
        snapshot.setDescription(description);
        snapshotRepository.save(snapshot);
        Log.infof("Updated metadata for snapshot '%s': label=%s", id, label);
        return true;
    }

    public boolean updateExpiration(String id, Instant expiresAt) {
        Optional<DatabaseSnapshot> opt = snapshotRepository.findById(id);
        if (opt.isEmpty()) return false;

        DatabaseSnapshot snapshot = opt.get();
        snapshot.setExpiresAt(expiresAt);
        snapshotRepository.save(snapshot);

        ScheduledFuture<?> existing = scheduledExpirations.remove(id);
        if (existing != null) existing.cancel(false);

        if (expiresAt != null) {
            scheduleExpiration(snapshot);
        }

        Log.infof("Updated expiration for snapshot '%s' to %s", id, expiresAt);
        return true;
    }

    public Path prepareForRestore(DatabaseSnapshot snapshot) throws IOException {
        Path storedPath = Path.of(storageDir).resolve(snapshot.getStoredFilename());
        if (!Files.exists(storedPath)) {
            throw new IOException("Snapshot file not found on disk: " + snapshot.getStoredFilename());
        }

        String suffix = snapshot.getFormat() == DatabaseSnapshot.Format.CUSTOM ? ".dump" : ".sql";
        Path tempFile = Files.createTempFile("snapshot-restore-", suffix);
        try (InputStream fis = Files.newInputStream(storedPath);
             java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(fis);
             OutputStream fos = Files.newOutputStream(tempFile)) {
            gzis.transferTo(fos);
        }
        return tempFile;
    }

    public void deleteSnapshot(String id) {
        ScheduledFuture<?> future = scheduledExpirations.remove(id);
        if (future != null) future.cancel(false);

        Optional<DatabaseSnapshot> opt = snapshotRepository.findById(id);
        if (opt.isEmpty()) return;

        DatabaseSnapshot snapshot = opt.get();
        Path storedPath = Path.of(storageDir).resolve(snapshot.getStoredFilename());
        try {
            Files.deleteIfExists(storedPath);
        } catch (IOException e) {
            Log.warnf("Failed to delete snapshot file %s: %s", storedPath, e.getMessage());
        }
        snapshotRepository.delete(id);
        Log.infof("Deleted snapshot '%s'", id);
    }

    public void cleanupFile(DatabaseSnapshot snapshot) {
        Path storedPath = Path.of(storageDir).resolve(snapshot.getStoredFilename());
        try {
            Files.deleteIfExists(storedPath);
        } catch (IOException e) {
            Log.warnf("Failed to cleanup snapshot file %s: %s", storedPath, e.getMessage());
        }
    }

    public java.io.File getStoredFile(DatabaseSnapshot snapshot) {
        return Path.of(storageDir).resolve(snapshot.getStoredFilename()).toFile();
    }

    public void markUsed(String id) {
        snapshotRepository.findById(id).ifPresent(snap -> {
            snap.setLastUsedAt(java.time.Instant.now());
            snapshotRepository.save(snap);
        });
    }

    public Optional<DatabaseSnapshot> findById(String id) {
        return snapshotRepository.findById(id);
    }

    public List<DatabaseSnapshot> findAll() {
        return snapshotRepository.findAll();
    }

    public long getTotalStorageBytes() {
        Path dir = Path.of(storageDir);
        if (!Files.isDirectory(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            Log.warnf("Failed to calculate snapshot storage size: %s", e.getMessage());
            return 0;
        }
    }

    private void scheduleExpiration(DatabaseSnapshot snapshot) {
        ScheduledFuture<?> existing = scheduledExpirations.remove(snapshot.getId());
        if (existing != null) existing.cancel(false);

        long delayMs = snapshot.getExpiresAt().toEpochMilli() - System.currentTimeMillis();
        if (delayMs <= 0) delayMs = 1;

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            Log.infof("Snapshot '%s' expired, deleting.", snapshot.getId());
            deleteSnapshot(snapshot.getId());
        }, delayMs, TimeUnit.MILLISECONDS);

        scheduledExpirations.put(snapshot.getId(), future);
    }

    public record StoreResult(long bytesWritten, String md5Hash) {}
}
