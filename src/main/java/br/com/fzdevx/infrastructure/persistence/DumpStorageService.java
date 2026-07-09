package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.application.port.DumpRepository;
import br.com.fzdevx.domain.exception.DuplicateDumpException;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class DumpStorageService {

    @Inject
    br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledExpirations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Inject
    DumpRepository dumpRepository;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    @ConfigProperty(name = "database.dump.enabled", defaultValue = "false")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "database.dump.storage.dir", defaultValue = "data/dumps/")
    String storageDir;

    @Inject
    @ConfigProperty(name = "database.dump.max-size-mb", defaultValue = "500")
    int maxSizeMb;

    void onStartup(@Observes StartupEvent event) {
        if (!enabled) return;
        List<DatabaseDump> dumps = dumpRepository.findAll();
        Log.infof("Reloading %d dump expirations.", dumps.size());
        for (DatabaseDump dump : dumps) {
            if (dump.getExpiresAt() == null) continue;
            if (dump.isExpired()) {
                Log.infof("Dump '%s' is past expiration, deleting now.", dump.getOriginalFilename());
                deleteDump(dump.getId());
            } else {
                scheduleExpiration(dump);
            }
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        scheduler.shutdownNow();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxSizeMb() {
        return maxSizeMb;
    }

    public boolean isStorageFull() {
        return getTotalStorageBytes() >= (long) maxSizeMb * 1024 * 1024;
    }

    public boolean validateUploadPassword(String password) {
        return passwordValidationService.validateUploadPassword(password);
    }

    public boolean validateOperationsPassword(String password) {
        return passwordValidationService.validateOperationsPassword(password);
    }

    public DatabaseDump storeUpload(InputStream input, String originalFilename,
                                     String databaseName, String version, Instant expiresAt,
                                     String description, String tenantId,
                                     java.util.List<String> sharedWithTenants) throws IOException {
        Optional<DatabaseDump> existingByName = dumpRepository.findByOriginalFilename(originalFilename);
        if (existingByName.isPresent()) {
            throw new DuplicateDumpException(
                    "A dump with the filename \"" + originalFilename + "\" already exists.");
        }

        Path dir = Path.of(storageDir);
        Files.createDirectories(dir);

        DatabaseDump dump = new DatabaseDump(originalFilename, databaseName, version, expiresAt, 0);
        dump.setCreatedBy(actorResolver.usernameOrSystem());
        dump.setTenantId(tenantId);
        dump.setSharedWithTenants(sharedWithTenants);
        Path storedPath = dir.resolve(dump.getStoredFilename());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }

        boolean alreadyGzipped = originalFilename.toLowerCase().endsWith(".gz");
        DigestInputStream digestInput = new DigestInputStream(input, digest);

        long bytesWritten;
        if (alreadyGzipped) {
            bytesWritten = Files.copy(digestInput, storedPath);
        } else {
            try (OutputStream fos = Files.newOutputStream(storedPath);
                 GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = digestInput.read(buffer)) != -1) {
                    gzos.write(buffer, 0, len);
                }
            }
            bytesWritten = Files.size(storedPath);
        }

        String hash = HexFormat.of().formatHex(digest.digest());

        Optional<DatabaseDump> existing = dumpRepository.findByMd5Hash(hash);
        if (existing.isPresent()) {
            Files.deleteIfExists(storedPath);
            throw new DuplicateDumpException(
                    "A file with the same content already exists: " + existing.get().getOriginalFilename());
        }

        dump.setMd5Hash(hash);
        dump.setFileSize(bytesWritten);
        dump.setDescription(description);

        // Detect actual format by reading magic bytes from the stored (gzipped) file
        DatabaseDump.Format detectedFormat = detectFormatFromContent(storedPath);
        if (detectedFormat != null) {
            dump.setFormat(detectedFormat);
        }

        dumpRepository.save(dump);
        if (expiresAt != null) {
            scheduleExpiration(dump);
        }
        Log.infof("Stored dump '%s' as '%s' (%d bytes, hash: %s, format: %s)",
                originalFilename, dump.getStoredFilename(), bytesWritten, hash, dump.getFormat());
        return dump;
    }

    private void scheduleExpiration(DatabaseDump dump) {
        ScheduledFuture<?> existing = scheduledExpirations.remove(dump.getId());
        if (existing != null) existing.cancel(false);

        long delayMs = dump.getExpiresAt().toEpochMilli() - System.currentTimeMillis();
        if (delayMs <= 0) delayMs = 1;

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            Log.infof("Dump '%s' expired, deleting.", dump.getOriginalFilename());
            deleteDump(dump.getId());
        }, delayMs, TimeUnit.MILLISECONDS);

        scheduledExpirations.put(dump.getId(), future);
    }

    // Field edits go through the repository's atomic update: two admins editing
    // different aspects of the same dump (metadata vs sharing vs expiration)
    // must not overwrite each other with a stale full-object save.

    public boolean updateMetadata(String id, String version, String databaseName, String description) {
        boolean updated = dumpRepository.update(id, dump -> {
            dump.setVersion(version);
            dump.setDatabaseName(databaseName);
            dump.setDescription(description);
        });
        if (updated) {
            Log.infof("Updated metadata for dump '%s': version=%s, database=%s", id, version, databaseName);
        }
        return updated;
    }

    /** Updates tenant sharing; owner change is applied only when requested (non-null). */
    public boolean updateSharing(String id, java.util.List<String> sharedWithTenants, String newTenantId, boolean changeOwner) {
        return dumpRepository.update(id, dump -> {
            dump.setSharedWithTenants(sharedWithTenants);
            if (changeOwner) {
                dump.setTenantId(newTenantId);
            }
        });
    }

    public boolean updateExpiration(String id, Instant expiresAt) {
        if (!dumpRepository.update(id, dump -> dump.setExpiresAt(expiresAt))) {
            return false;
        }

        // Cancel existing schedule
        ScheduledFuture<?> existing = scheduledExpirations.remove(id);
        if (existing != null) existing.cancel(false);

        // Reschedule or leave unscheduled
        if (expiresAt != null) {
            dumpRepository.findById(id).ifPresent(this::scheduleExpiration);
        }

        Log.infof("Updated expiration for dump '%s' to %s", id, expiresAt);
        return true;
    }

    public Path prepareForRestore(DatabaseDump dump) throws IOException {
        Path storedPath = Path.of(storageDir).resolve(dump.getStoredFilename());
        if (!Files.exists(storedPath)) {
            throw new IOException("Dump file not found on disk: " + dump.getStoredFilename());
        }

        Path tempFile = Files.createTempFile("dump-restore-", getSuffix(dump));
        try (InputStream fis = Files.newInputStream(storedPath);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             OutputStream fos = Files.newOutputStream(tempFile)) {
            gzis.transferTo(fos);
        }
        return tempFile;
    }

    /**
     * Reads the first 5 bytes of the decompressed content to detect PostgreSQL custom-format dumps.
     * Custom-format dumps always start with "PGDMP" magic bytes, regardless of file extension.
     */
    private DatabaseDump.Format detectFormatFromContent(Path gzippedFile) {
        try (InputStream fis = Files.newInputStream(gzippedFile);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {
            byte[] magic = new byte[5];
            int read = gzis.readNBytes(magic, 0, 5);
            if (read == 5 && new String(magic).equals("PGDMP")) {
                return DatabaseDump.Format.CUSTOM;
            }
        } catch (IOException e) {
            Log.warnf("Could not detect dump format from content: %s", e.getMessage());
        }
        return null;
    }

    private String getSuffix(DatabaseDump dump) {
        return switch (dump.getFormat()) {
            case SQL -> ".sql";
            case CUSTOM -> ".dump";
            case COMPRESSED -> ".dump";
        };
    }

    public void cleanupTempFile(Path tempFile) {
        try {
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            Log.warnf("Failed to cleanup temp file %s: %s", tempFile, e.getMessage());
        }
    }

    public void deleteDump(String id) {
        ScheduledFuture<?> future = scheduledExpirations.remove(id);
        if (future != null) future.cancel(false);

        Optional<DatabaseDump> opt = dumpRepository.findById(id);
        if (opt.isEmpty()) return;

        DatabaseDump dump = opt.get();
        Path storedPath = Path.of(storageDir).resolve(dump.getStoredFilename());
        try {
            Files.deleteIfExists(storedPath);
        } catch (IOException e) {
            Log.warnf("Failed to delete dump file %s: %s", storedPath, e.getMessage());
        }
        dumpRepository.delete(id);
        Log.infof("Deleted dump '%s' (id: %s)", dump.getOriginalFilename(), id);
    }

    public java.io.File getStoredFile(DatabaseDump dump) {
        return Path.of(storageDir).resolve(dump.getStoredFilename()).toFile();
    }

    public void markUsed(String id) {
        // atomic - a download racing an admin's metadata/sharing edit must not
        // roll that edit back with a stale full-object save
        dumpRepository.update(id, dump -> dump.setLastUsedAt(java.time.Instant.now()));
    }

    public Optional<DatabaseDump> findById(String id) {
        return dumpRepository.findById(id);
    }

    public List<DatabaseDump> findAll() {
        return dumpRepository.findAll();
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
            Log.warnf("Failed to calculate storage size: %s", e.getMessage());
            return 0;
        }
    }
}
