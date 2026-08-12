package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.AuditEntry;

import java.util.List;

/** One page of audit entries (newest first) plus the total match count. */
public record AuditSearchResult(List<AuditEntry> entries, long total) {
}
