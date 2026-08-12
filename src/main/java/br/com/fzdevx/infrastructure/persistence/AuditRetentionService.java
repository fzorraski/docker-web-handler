package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.infrastructure.config.RuntimeSettingsService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes audit entries older than the configured retention. The threshold is
 * the runtime setting "auditRetentionDays" (property audit.retention-days),
 * editable by super admins without a restart; 0 disables cleanup entirely.
 * Runs shortly after startup and then once a day, plus immediately whenever
 * the setting is changed (see ManageSettingsUseCase).
 */
@ApplicationScoped
public class AuditRetentionService {

    @Inject
    RuntimeSettingsService runtimeSettingsService;

    @Inject
    br.com.fzdevx.application.port.AuditLogger auditLogger;

    @Inject
    ActivitySummaryService activitySummaryService;

    private ScheduledExecutorService scheduler;

    void onStartup(@Observes StartupEvent event) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-retention");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::cleanupNow, 1, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
    }

    /** Applies the current retention setting; safe to call from any thread. */
    public void cleanupNow() {
        try {
            int retentionDays = runtimeSettingsService.getAuditRetentionDays();
            if (retentionDays <= 0) {
                return;
            }
            // Roll the trail up BEFORE deleting any of it, or the counts behind
            // the reports are lost with the entries. This matters most on the
            // path that is not a timer at all: lowering auditRetentionDays in
            // the admin UI calls this synchronously (ManageSettingsUseCase), so
            // without it a settings change would purge unsummarised days on the
            // spot. The roll-up runs UNCAPPED here on purpose: the DELETE below
            // is unbounded, so stopping at the per-run cap would purge everything
            // older than it without ever summarising it.
            try {
                activitySummaryService.summariseEverything();
            } catch (Exception e) {
                // deliberately not fatal: a stuck roll-up must not stop retention
                // and let the audit table grow without bound. Data may be lost.
                Log.errorf(e, "Activity roll-up before audit retention failed; "
                        + "entries are about to be deleted without being summarised.");
            }

            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            int removed = auditLogger.removeEntriesOlderThan(cutoff);
            if (removed > 0) {
                Log.infof("Audit retention: removed %d entr%s older than %d day(s).",
                        removed, removed == 1 ? "y" : "ies", retentionDays);
            }
        } catch (Exception e) {
            Log.errorf(e, "Audit retention cleanup failed.");
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
