package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ActivityOverview;
import br.com.fzdevx.application.dto.ActivityOverviewCriteria;
import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.application.usecase.BuildActivityOverviewUseCase;
import br.com.fzdevx.domain.model.auth.User;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.persistence.ActivitySummaryService;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserActivityRepository;
import java.util.Locale;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Per-user activity reports built from the daily audit roll-up. Reserved for
 * super admins: the numbers span every tenant.
 */
@Path("/reports/activity")
@RequiresPermission(Permission.SYSTEM_CONFIG)
public class ActivityReportController {

    @Inject
    PgUserActivityRepository activityRepository;

    @Inject
    ActivitySummaryService activitySummaryService;

    @Inject
    BuildActivityOverviewUseCase buildOverview;

    @Inject
    UserRepository userRepository;

    /**
     * Lowercase key so an attempted "Admin" still matches the registered
     * "admin"; the canonical casing is kept as the value for display.
     */
    private Map<String, String> registeredUsernames() {
        return userRepository.findAll().stream()
                .collect(Collectors.toMap(
                        u -> u.getUsername().toLowerCase(Locale.ROOT),
                        User::getUsername,
                        (a, b) -> a));
    }

    /** Day-by-day rows, newest first. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> search(@QueryParam("page") int page,
                                      @QueryParam("size") int size,
                                      @QueryParam("from") String from,
                                      @QueryParam("to") String to,
                                      @QueryParam("actor") String actor,
                                      @QueryParam("action") String action) {
        requireActive();
        return respond(activityRepository.search(criteria(page, size, from, to, actor, action),
                activitySummaryService.zone()), page, size);
    }

    /**
     * Everything the dashboard draws for one window: totals against the
     * preceding window, the daily trend, the user ranking, action and tenant
     * breakdowns, failures and the heatmap.
     *
     * <p>Defaults to the last 30 days ending today. Today is only on the screen
     * because the query falls through to the raw audit trail past the roll-up's
     * watermark; without that the newest thing a dashboard could show would be
     * yesterday.</p>
     */
    @GET
    @Path("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public ActivityOverview overview(@QueryParam("from") String from,
                                     @QueryParam("to") String to,
                                     @QueryParam("tenant") String tenant,
                                     @QueryParam("topUsers") int topUsers,
                                     @QueryParam("topActions") int topActions) {
        requireActive();
        LocalDate today = LocalDate.now(activitySummaryService.zone());
        LocalDate end = orDefault(parseDate(to, "to"), today);
        LocalDate start = orDefault(parseDate(from, "from"), end.minusDays(29));
        if (start.isAfter(end)) {
            throw new InvalidInputException("The 'from' date must not be after the 'to' date.");
        }

        ActivityOverviewCriteria criteria =
                new ActivityOverviewCriteria(start, end, tenant, topUsers, topActions);
        if (criteria.days() > ActivityOverviewCriteria.MAX_RANGE_DAYS) {
            throw new InvalidInputException("The range is limited to "
                    + ActivityOverviewCriteria.MAX_RANGE_DAYS + " days.");
        }

        // one fetch spanning both windows - the comparison is a split, not a
        // second scan of the same rows
        return buildOverview.build(
                activityRepository.rowsForRange(criteria.previousFrom(), criteria.to(), tenant,
                        activitySummaryService.zone()),
                criteria,
                activityRepository.summarisedThrough().orElse(null),
                registeredUsernames());
    }

    /** Totals per user and action over the whole range. */
    @GET
    @Path("/by-user")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> byUser(@QueryParam("page") int page,
                                      @QueryParam("size") int size,
                                      @QueryParam("from") String from,
                                      @QueryParam("to") String to,
                                      @QueryParam("actor") String actor,
                                      @QueryParam("action") String action) {
        requireActive();
        return respond(activityRepository.totalsByUser(criteria(page, size, from, to, actor, action),
                activitySummaryService.zone()), page, size);
    }

    /** Action names present in the summary, for the filter dropdown. */
    @GET
    @Path("/actions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> actions() {
        if (!activitySummaryService.active()) {
            return List.of();
        }
        return activityRepository.distinctActions();
    }

    /**
     * Whether the roll-up is running at all, so the screen can explain an empty
     * table instead of looking broken (it is inactive on the file backend).
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        return Map.of("active", activitySummaryService.active());
    }

    /**
     * The tables only exist on the PostgreSQL backend; querying them in file
     * mode would 500 instead of showing the "roll-up not running" notice that
     * /status drives.
     */
    private void requireActive() {
        if (!activitySummaryService.active()) {
            throw new InvalidInputException(
                    "The activity roll-up is not running, so there is nothing to report. "
                            + "It requires the PostgreSQL persistence backend.");
        }
    }

    private ActivityReportCriteria criteria(int page, int size, String from, String to,
                                            String actor, String action) {
        return new ActivityReportCriteria(parseDate(from, "from"), parseDate(to, "to"),
                actor, action, page, size);
    }

    private Map<String, Object> respond(ActivityReportResult result, int page, int size) {
        ActivityReportCriteria applied = new ActivityReportCriteria(null, null, null, null, page, size);
        return Map.of(
                "rows", result.rows(),
                "total", result.total(),
                "page", applied.page(),
                "size", applied.size());
    }

    private static LocalDate orDefault(LocalDate value, LocalDate fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidInputException(
                    "Invalid '" + field + "' date - use ISO-8601 (e.g. 2026-08-12).");
        }
    }
}
