package br.com.fzdevx.domain.model;

import java.time.Instant;

/**
 * One recorded audit event: who did what to which target, and when.
 *
 * <p>{@code tenantId} is the acting user's tenant at the time of the action, or
 * null when the action belongs to no tenant (system job, tenantless user, RBAC
 * off). It scopes who may read the entry back.</p>
 */
public record AuditEntry(Instant timestamp, String actor, String action, String target, String detail,
                         String tenantId) {
}
