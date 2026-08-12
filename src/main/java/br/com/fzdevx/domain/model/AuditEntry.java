package br.com.fzdevx.domain.model;

import java.time.Instant;

/** One recorded audit event: who did what to which target, and when. */
public record AuditEntry(Instant timestamp, String actor, String action, String target, String detail) {
}
