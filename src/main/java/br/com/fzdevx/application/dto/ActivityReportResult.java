package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.UserActivitySummary;

import java.util.List;

/** One page of activity rows plus the total number of matching rows. */
public record ActivityReportResult(List<UserActivitySummary> rows, long total) {
}
