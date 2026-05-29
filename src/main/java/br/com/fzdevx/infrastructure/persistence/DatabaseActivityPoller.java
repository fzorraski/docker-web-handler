package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.config.AllowedRepositoryResolver;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically samples {@code pg_stat_activity} (client-backend sessions only)
 * and persists each database's most recent activity timestamp to its
 * {@code ManagedDatabase.appLastUsedAt}, so the "idle since" UI keeps showing
 * the right value even after sessions disconnect.
 *
 * <p>Performance budget per cycle on a host with N repositories and M databases:
 * <ul>
 *   <li>One PG connection + one query per repository ({@code O(M)} per repo, but
 *       a single round-trip, very fast).</li>
 *   <li>One JSON file rewrite per repository, only when something actually
 *       changed (via {@link ManagedDatabaseRepository#bulkMarkUsed}).</li>
 *   <li>Zero cache invalidation — the {@code ListManagedDatabasesUseCase} cache
 *       refreshes naturally on TTL expiry and picks up the persisted values.</li>
 * </ul>
 * Single-threaded scheduler, runs at fixed delay so a slow cycle never overlaps
 * itself. Per-repository try/catch so one bad PG never breaks the others.
 */
@ApplicationScoped
public class DatabaseActivityPoller {

    @Inject
    DatabaseService databaseService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    AllowedRepositoryResolver allowedRepositoryResolver;

    @Inject
    @ConfigProperty(name = "database.activity.poll.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "database.activity.poll.interval-seconds", defaultValue = "60")
    long intervalSeconds;

    @Inject
    @ConfigProperty(name = "database.activity.poll.initial-delay-seconds", defaultValue = "30")
    long initialDelaySeconds;

    private ScheduledExecutorService scheduler;

    void onStartup(@Observes StartupEvent event) {
        if (!enabled) {
            Log.info("Database activity poller is disabled (database.activity.poll.enabled=false).");
            return;
        }
        if (intervalSeconds <= 0) {
            Log.warnf("Database activity poller disabled: interval-seconds must be > 0 (got %d).",
                    intervalSeconds);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-activity-poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runCycle,
                Math.max(0, initialDelaySeconds), intervalSeconds, TimeUnit.SECONDS);
        Log.infof("Database activity poller started (interval=%ds, initial-delay=%ds).",
                intervalSeconds, initialDelaySeconds);
        // Fire-and-forget permission diagnostic; isolated from the polling
        // schedule so a slow/unreachable PG doesn't delay startup.
        scheduler.schedule(this::reportPermissionDiagnostics, 1, TimeUnit.SECONDS);
    }

    private void reportPermissionDiagnostics() {
        List<String> repositories;
        try {
            repositories = allowedRepositoryResolver.getAllowed().stream()
                    .filter(databaseService::hasDatabaseConfig)
                    .toList();
        } catch (Exception e) {
            return;
        }
        for (String repository : repositories) {
            try {
                DatabaseService.ActivityPermissionCheck check =
                        databaseService.checkActivityPermissions(repository);
                if (check == null) continue;
                if (!check.canSeeOtherSessions()) {
                    Log.warnf(
                            "Activity poller: repository '%s' connects as '%s' which is NOT superuser "
                            + "and lacks pg_read_all_stats. Sessions from OTHER users in pg_stat_activity "
                            + "will have NULL state_change/query_start/xact_start, so their activity "
                            + "will be invisible to the poller. Grant: GRANT pg_read_all_stats TO %s;",
                            repository, check.currentUser(), check.currentUser());
                } else {
                    Log.debugf("Activity poller: repository '%s' permission OK (user=%s, superuser=%s, read_all_stats=%s, pg=%s).",
                            repository, check.currentUser(),
                            check.isSuperuser(), check.hasReadAllStats(),
                            check.serverVersion());
                }
            } catch (Exception e) {
                Log.warnf("Activity poller: permission check failed for '%s': %s",
                        repository, e.getMessage());
            }
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Visible for tests. */
    void runCycle() {
        List<String> repositories;
        try {
            repositories = allowedRepositoryResolver.getAllowed().stream()
                    .filter(databaseService::hasDatabaseConfig)
                    .toList();
        } catch (Exception e) {
            Log.warnf("Activity poller: failed to resolve repository list: %s", e.getMessage());
            return;
        }
        for (String repository : repositories) {
            try {
                pollRepository(repository);
            } catch (Exception e) {
                // Per-repository isolation — one bad repo never breaks the others.
                Log.warnf("Activity poller: repository '%s' failed: %s", repository, e.getMessage());
            }
        }
    }

    private void pollRepository(String repository) {
        Map<String, Instant> activity = databaseService.getClientBackendActivity(repository);
        if (activity.isEmpty()) {
            return;
        }
        // bulkMarkUsed is a single write-lock + single file rewrite, and skips
        // entries whose timestamp would regress, so this is safe to call even if
        // the cached values are already current.
        managedDatabaseRepository.bulkMarkUsed(repository, activity);
        Log.debugf("Activity poller: repository '%s' — observed %d active database(s).",
                repository, activity.size());
    }
}
