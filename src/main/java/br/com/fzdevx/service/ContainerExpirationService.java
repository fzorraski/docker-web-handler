package br.com.fzdevx.service;

import br.com.fzdevx.model.ContainerExpiration;
import br.com.fzdevx.repository.ExpirationRepository;
import com.github.dockerjava.api.DockerClient;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

@ApplicationScoped
public class ContainerExpirationService {

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @Inject
    DockerClient dockerClient;

    @Inject
    ExpirationRepository expirationRepository;

    void onStartup(@Observes StartupEvent event) {
        reloadExpirations();
    }

    public void schedule(String shortId, String fullContainerId, Instant expiresAt) {
        ContainerExpiration expiration = new ContainerExpiration(shortId, fullContainerId, expiresAt);
        expirationRepository.save(expiration);
        scheduleTask(expiration);
    }

    public void cancel(String shortId) {
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

    void onShutdown(@Observes ShutdownEvent event) {
        scheduler.shutdownNow();
    }

    private void reloadExpirations() {
        List<ContainerExpiration> persisted = expirationRepository.findAll();
        Log.infof("Reloading %d persisted container expirations.", persisted.size());

        for (ContainerExpiration expiration : persisted) {
            if (expiration.isExpired()) {
                Log.infof("Container %s expiration is past due, executing now.", expiration.getShortId());
                executeExpiration(expiration);
            } else {
                scheduleTask(expiration);
            }
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
        } catch (Exception e) {
            Log.errorf("Failed to expire container %s: %s", expiration.getShortId(), e.getMessage());
        } finally {
            scheduledTasks.remove(expiration.getShortId());
            expirationRepository.delete(expiration.getShortId());
        }
    }
}
