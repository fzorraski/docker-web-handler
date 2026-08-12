package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ActivityReportCriteria;
import br.com.fzdevx.application.dto.ActivityReportResult;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.persistence.ActivitySummaryService;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserActivityRepository;
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

    /** Day-by-day rows, newest first. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> search(@QueryParam("page") int page,
                                      @QueryParam("size") int size,
                                      @QueryParam("from") String from,
                                      @QueryParam("to") String to,
                                      @QueryParam("actor") String actor,
                                      @QueryParam("action") String action) {
        return respond(activityRepository.search(criteria(page, size, from, to, actor, action)),
                page, size);
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
        return respond(activityRepository.totalsByUser(criteria(page, size, from, to, actor, action)),
                page, size);
    }

    /** Action names present in the summary, for the filter dropdown. */
    @GET
    @Path("/actions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> actions() {
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
