package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserActivityRepository;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rolls the audit trail up into per-user daily totals so reports stay fast and
 * survive audit retention: once entries are purged, these counts are the only
 * remaining history.
 *
 * <p>The timer is only a trigger. Which days get summarised is decided from the
 * persisted watermark on every tick, so restarts, downtime and drift cannot skip
 * a day - unlike a plain "every 24 hours" schedule, which is relative to process
 * uptime and never fires on a service that restarts more often than that.</p>
 *
 * <p>Only complete days are summarised: today is still accumulating. Each day is
 * recomputed from source and upserted, so a retry - or a second instance firing
 * the same timer, which this codebase has no leader election to prevent - lands
 * on the same numbers.</p>
 *
 * <p>PostgreSQL only. On the legacy file backend the job stays inactive.</p>
 */
@ApplicationScoped
public class ActivitySummaryService {

    @Inject
    PersistenceBackendProducer backendProducer;

    @Inject
    PgUserActivityRepository activityRepository;

    @Inject
    @ConfigProperty(name = "activity.summary.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "activity.summary.interval-minutes", defaultValue = "60")
    int intervalMinutes;

    @Inject
    @ConfigProperty(name = "activity.summary.max-days-per-run", defaultValue = "90")
    int maxDaysPerRun;

    // Optional, not a String with an empty default: a blank property value
    // reads as "unset" and Quarkus refuses to bind it to a String
    @Inject
    @ConfigProperty(name = "activity.summary.zone")
    Optional<String> zoneId;

    private ScheduledExecutorService scheduler;

    void onStartup(@Observes StartupEvent event) {
        if (!active()) {
            Log.debug("Activity summary is inactive (disabled, or not running on PostgreSQL).");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "activity-summary");
            t.setDaemon(true);
            return t;
        });
        // hourly rather than daily: the tick only asks "is there a complete day
        // I have not summarised yet", so a frequent tick is what makes a restart
        // unable to skip one, and picks the new day up shortly after midnight
        scheduler.scheduleWithFixedDelay(this::summariseNow, 2, Math.max(1, intervalMinutes),
                TimeUnit.MINUTES);
    }

    /**
     * Summarises every complete day since the watermark, up to the per-run cap.
     * Safe to call from any thread and never throws - the retention job calls it
     * before purging, and a scheduled task that escapes an exception is cancelled
     * forever.
     */
    public void summariseNow() {
        try {
            if (!active()) {
                return;
            }
            LocalDate lastComplete = LocalDate.now(zone()).minusDays(1);
            Optional<LocalDate> watermark = activityRepository.summarisedThrough();

            LocalDate from;
            if (watermark.isPresent()) {
                from = watermark.get().plusDays(1);
            } else {
                Optional<Instant> oldest = activityRepository.oldestAuditEntry();
                if (oldest.isEmpty()) {
                    // nothing to summarise yet; park the watermark so the first
                    // real run does not rescan history that never existed
                    activityRepository.setSummarisedThrough(lastComplete);
                    return;
                }
                from = LocalDate.ofInstant(oldest.get(), zone());
            }
            if (from.isAfter(lastComplete)) {
                return;
            }

            int days = 0;
            long rows = 0;
            for (LocalDate day = from; !day.isAfter(lastComplete) && days < maxDaysPerRun;
                 day = day.plusDays(1)) {
                rows += activityRepository.rollUpDay(day, startOf(day), startOf(day.plusDays(1)));
                days++;
            }
            if (days > 0) {
                Log.infof("Activity summary: rolled up %d day(s) through %s (%d row(s)).",
                        days, from.plusDays(days - 1), rows);
            }
        } catch (Exception e) {
            Log.errorf(e, "Activity summary roll-up failed.");
        }
    }

    /** Whether the roll-up can run at all. */
    public boolean active() {
        return enabled && backendProducer.isPostgres();
    }

    /**
     * Start of a local calendar day as an instant. Going through the zone means
     * DST is handled for free - a local day is simply 23, 24 or 25 hours - and
     * the half-open instant range can use the index on audit_log.occurred_at,
     * which wrapping the column in a timezone conversion could not.
     */
    private Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone()).toInstant();
    }

    private ZoneId zone() {
        String configured = zoneId == null ? null : zoneId.filter(z -> !z.isBlank()).orElse(null);
        if (configured == null) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException e) {
            Log.warnf("Invalid activity.summary.zone '%s', using the server default.", configured);
            return ZoneId.systemDefault();
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
