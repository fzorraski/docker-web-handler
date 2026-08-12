package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AuditScope;
import br.com.fzdevx.application.dto.AuditSearchCriteria;
import br.com.fzdevx.application.dto.AuditSearchResult;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Read-only browsing of the audit trail for users holding AUDIT_LOG_VIEW. */
@Path("/audit")
@RequiresPermission(Permission.AUDIT_LOG_VIEW)
public class AuditController {

    @Inject
    AuditLogger auditLogger;

    @Inject
    br.com.fzdevx.infrastructure.config.TenantVisibility tenantVisibility;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> search(@QueryParam("page") int page,
                                      @QueryParam("size") int size,
                                      @QueryParam("actor") String actor,
                                      @QueryParam("action") String action,
                                      @QueryParam("q") String text,
                                      @QueryParam("from") String from,
                                      @QueryParam("to") String to) {
        AuditSearchCriteria criteria = new AuditSearchCriteria(
                actor, action, text, parseInstant(from, "from"), parseInstant(to, "to"), page, size);
        AuditSearchResult result = auditLogger.search(criteria, scope());
        return Map.of(
                "entries", result.entries(),
                "total", result.total(),
                "page", criteria.page(),
                "size", criteria.size());
    }

    @GET
    @Path("/actions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> actions() {
        return auditLogger.distinctActions(scope());
    }

    /** A reader without cross-tenant reach only ever sees their own tenants' entries. */
    private AuditScope scope() {
        return tenantVisibility.auditScope();
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidInputException("Invalid '" + field + "' timestamp - use ISO-8601 (e.g. 2026-07-17T00:00:00Z).");
        }
    }
}
