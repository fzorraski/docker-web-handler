package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.interfaces.rest.util.ContainerListBroadcaster;
import com.github.dockerjava.api.DockerClient;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

@ApplicationScoped
public class ContainerExpirationService {

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Inject
    DockerClient dockerClient;

    @Inject
    ExpirationRepository expirationRepository;

    @Inject
    DatabaseService databaseService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ContainerSchedulingService schedulingService;

    @Inject
    ContainerListBroadcaster broadcaster;

    void onStartup(@Observes StartupEvent event) {
        reloadExpirations();
    }

    public void schedule(String shortId, String fullContainerId, Instant expiresAt) {
        schedule(shortId, fullContainerId, expiresAt, null, null, false);
    }

    public void schedule(String shortId, String fullContainerId, Instant expiresAt,
                         String repository, String databaseName, boolean deleteDatabaseOnExpiration) {
        ContainerExpiration expiration = new ContainerExpiration(shortId, fullContainerId, expiresAt,
                repository, databaseName, deleteDatabaseOnExpiration);
        expirationRepository.save(expiration);
        scheduleTask(expiration);
    }

    public void saveMetadata(String shortId, String fullContainerId, String repository, String databaseName) {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId(shortId);
        expiration.setFullContainerId(fullContainerId);
        expiration.setRepository(repository);
        expiration.setDatabaseName(databaseName);
        expirationRepository.save(expiration);
    }

    public void cancel(String shortId) {
        ScheduledFuture<?> future = scheduledTasks.remove(shortId);
        if (future != null) {
            future.cancel(false);
        }
        expirationRepository.findByContainerId(shortId).ifPresentOrElse(expiration -> {
            if (expiration.getDatabaseName() != null && !expiration.getDatabaseName().isBlank()) {
                expiration.setExpiresAt(null);
                expiration.setDeleteDatabaseOnExpiration(false);
                expirationRepository.save(expiration);
            } else {
                expirationRepository.delete(shortId);
            }
        }, () -> expirationRepository.delete(shortId));
    }

    public void remove(String shortId) {
        ScheduledFuture<?> future = scheduledTasks.remove(shortId);
        if (future != null) {
            future.cancel(false);
        }
        expirationRepository.delete(shortId);
    }

    public Instant getExpiresAt(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .map(ContainerExpiration::getExpiresAt)
                .orElse(null);
    }

    public String getDatabaseName(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .map(ContainerExpiration::getDatabaseName)
                .orElse(null);
    }

    public boolean isDeleteDatabaseOnExpiration(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .map(ContainerExpiration::isDeleteDatabaseOnExpiration)
                .orElse(false);
    }

    public String getRepository(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .map(ContainerExpiration::getRepository)
                .orElse(null);
    }

    public List<ContainerExpiration> findByDatabaseName(String databaseName) {
        return expirationRepository.findByDatabaseName(databaseName);
    }

    public List<ContainerExpiration> findAll() {
        return expirationRepository.findAll();
    }

    public boolean extendExpiration(String shortId, int minutes) {
        return expirationRepository.findByContainerId(shortId)
                .filter(expiration -> expiration.getExpiresAt() != null)
                .map(expiration -> {
                    Instant newExpiresAt = expiration.getExpiresAt().plusSeconds(minutes * 60L);
                    expiration.setExpiresAt(newExpiresAt);
                    expirationRepository.save(expiration);
                    scheduleTask(expiration);
                    return true;
                })
                .orElse(false);
    }

    public boolean disableDatabaseDeletion(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .map(expiration -> {
                    expiration.setDeleteDatabaseOnExpiration(false);
                    expirationRepository.save(expiration);
                    return true;
                })
                .orElse(false);
    }

    public boolean updateExpiration(String shortId, Instant expiresAt, boolean deleteDatabaseOnExpiration) {
        Optional<ContainerExpiration> existing = expirationRepository.findByContainerId(shortId);
        if (existing.isPresent()) {
            ContainerExpiration expiration = existing.get();
            ScheduledFuture<?> future = scheduledTasks.remove(shortId);
            if (future != null) {
                future.cancel(false);
            }
            if (expiresAt == null) {
                expiration.setExpiresAt(null);
                expiration.setDeleteDatabaseOnExpiration(false);
            } else {
                expiration.setExpiresAt(expiresAt);
                expiration.setDeleteDatabaseOnExpiration(deleteDatabaseOnExpiration);
                scheduleTask(expiration);
            }
            expirationRepository.save(expiration);
            return true;
        }
        // No record exists — create one if we have an expiration time.
        // Synchronized to prevent two concurrent requests from both creating a record.
        if (expiresAt == null) {
            return false;
        }
        synchronized (this) {
            // Re-check after acquiring lock
            if (expirationRepository.findByContainerId(shortId).isPresent()) {
                return updateExpiration(shortId, expiresAt, deleteDatabaseOnExpiration);
            }
            String fullContainerId = resolveFullContainerId(shortId);
            if (fullContainerId == null) {
                return false;
            }
            schedule(shortId, fullContainerId, expiresAt, null, null, deleteDatabaseOnExpiration);
            return true;
        }
    }

    private String resolveFullContainerId(String shortId) {
        try {
            for (com.github.dockerjava.api.model.Container c :
                    dockerClient.listContainersCmd().withShowAll(true).exec()) {
                if (c.getId().startsWith(shortId)) {
                    return c.getId();
                }
            }
        } catch (Exception e) {
            Log.warnf("Failed to resolve full container ID for %s: %s", shortId, e.getMessage());
        }
        return null;
    }

    public boolean enableDatabaseDeletion(String shortId) {
        return expirationRepository.findByContainerId(shortId)
                .filter(expiration -> expiration.getExpiresAt() != null)
                .filter(expiration -> expiration.getDatabaseName() != null
                        && !expiration.getDatabaseName().isBlank())
                .map(expiration -> {
                    expiration.setDeleteDatabaseOnExpiration(true);
                    expirationRepository.save(expiration);
                    return true;
                })
                .orElse(false);
    }

    void onShutdown(@Observes ShutdownEvent event) {
        scheduler.shutdownNow();
    }

    private void reloadExpirations() {
        List<ContainerExpiration> persisted = expirationRepository.findAll();
        Log.infof("Reloading %d persisted container expirations.", persisted.size());

        Set<String> existingContainerIds = resolveExistingContainerIds();

        for (ContainerExpiration expiration : persisted) {
            if (!existingContainerIds.contains(expiration.getShortId())) {
                Log.infof("Removing orphaned expiration record for container %s (no longer exists).",
                        expiration.getShortId());
                expirationRepository.delete(expiration.getShortId());
                continue;
            }
            if (expiration.getExpiresAt() == null) {
                continue;
            }
            if (expiration.isExpired()) {
                Log.infof("Container %s expiration is past due, executing now.", expiration.getShortId());
                executeExpiration(expiration);
            } else {
                scheduleTask(expiration);
            }
        }
    }

    private Set<String> resolveExistingContainerIds() {
        try {
            Set<String> ids = new java.util.HashSet<>();
            for (com.github.dockerjava.api.model.Container c :
                    dockerClient.listContainersCmd().withShowAll(true).exec()) {
                ids.add(c.getId().substring(0, 10));
            }
            return ids;
        } catch (Exception e) {
            Log.warnf("Failed to list containers for orphan cleanup: %s", e.getMessage());
            return Collections.emptySet();
        }
    }

    private void scheduleTask(ContainerExpiration expiration) {
        ScheduledFuture<?> existing = scheduledTasks.remove(expiration.getShortId());
        if (existing != null) {
            existing.cancel(false);
        }

        long delayMs = expiration.getExpiresAt().toEpochMilli() - System.currentTimeMillis();
        if (delayMs <= 0) delayMs = 1;

        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeExpiration(expiration),
                delayMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(expiration.getShortId(), future);
    }

    private void executeExpiration(ContainerExpiration expiration) {
        try {
            try {
                dockerClient.stopContainerCmd(expiration.getFullContainerId()).exec();
            } catch (Exception ignored) {
            }
            dockerClient.removeContainerCmd(expiration.getFullContainerId()).exec();
            Log.infof("Container %s expired and was removed.", expiration.getShortId());
            broadcaster.notifyChange();
        } catch (Exception e) {
            Log.errorf("Failed to expire container %s: %s", expiration.getShortId(), e.getMessage());
        } finally {
            dropDatabaseIfConfigured(expiration);
            schedulingService.removeSchedulesByContainer(expiration.getShortId());
            scheduledTasks.remove(expiration.getShortId());
            expirationRepository.delete(expiration.getShortId());
        }
    }

    private void dropDatabaseIfConfigured(ContainerExpiration expiration) {
        // Re-read from repository to reflect any runtime changes (e.g. user cancelled DB deletion)
        ContainerExpiration current = expirationRepository.findByContainerId(expiration.getShortId())
                .orElse(expiration);
        if (!current.isDeleteDatabaseOnExpiration()) {
            return;
        }
        if (current.getRepository() == null || current.getDatabaseName() == null) {
            return;
        }
        if (!databaseService.isDeletionOnExpirationEnabled()) {
            return;
        }
        if (!databaseService.hasDatabaseConfig(current.getRepository())) {
            return;
        }
        // Check if database is protected
        boolean isProtected = managedDatabaseRepository.find(current.getRepository(), current.getDatabaseName())
                .map(ManagedDatabase::isProtectedFlag).orElse(false);
        if (isProtected) {
            Log.infof("Skipping deletion of protected database '%s' on container %s expiration.",
                    current.getDatabaseName(), current.getShortId());
            return;
        }
        try {
            databaseService.dropDatabase(current.getRepository(), current.getDatabaseName());
            Log.infof("Database '%s' dropped on expiration of container %s.",
                    current.getDatabaseName(), current.getShortId());
            removeContainersByDatabase(current.getDatabaseName(), current.getShortId());
        } catch (Exception e) {
            Log.errorf("Failed to drop database '%s' on expiration of container %s: %s",
                    current.getDatabaseName(), current.getShortId(), e.getMessage());
        }
    }

    private void removeContainersByDatabase(String databaseName, String excludeShortId) {
        List<ContainerExpiration> others = expirationRepository.findByDatabaseName(databaseName);
        boolean changed = false;
        for (ContainerExpiration other : others) {
            if (other.getShortId().equals(excludeShortId)) {
                continue;
            }
            Log.infof("Removing container %s because database '%s' was dropped.",
                    other.getShortId(), databaseName);
            try {
                try {
                    dockerClient.stopContainerCmd(other.getFullContainerId()).exec();
                } catch (Exception ignored) {
                }
                dockerClient.removeContainerCmd(other.getFullContainerId()).exec();
                Log.infof("Container %s removed (database '%s' no longer exists).",
                        other.getShortId(), databaseName);
                changed = true;
            } catch (Exception e) {
                Log.errorf("Failed to remove container %s after database drop: %s",
                        other.getShortId(), e.getMessage());
            } finally {
                ScheduledFuture<?> future = scheduledTasks.remove(other.getShortId());
                if (future != null) {
                    future.cancel(false);
                }
                expirationRepository.delete(other.getShortId());
            }
        }
        if (changed) broadcaster.notifyChange();
    }
}
