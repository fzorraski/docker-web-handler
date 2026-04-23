/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — job execution querying with filter/sort/paginate),
 *        D (depends on domain model only)
 * Behavior: identical to original LogAnalyzerController#getJobs and #getJobFilters
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.JobExecution;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QueryJobExecutionsUseCase {

    public PaginatedResult<JobExecution> query(List<JobExecution> jobExecutions,
                                               String jobName, String thread, String status,
                                               String sort, String sortDir, int page, int size) {
        var jobs = jobExecutions.stream();
        if (jobName != null && !jobName.isBlank()) {
            jobs = jobs.filter(j -> jobName.equals(j.jobName()));
        }
        if (thread != null && !thread.isBlank()) {
            jobs = jobs.filter(j -> thread.equals(j.thread()));
        }
        if ("failed".equalsIgnoreCase(status)) {
            jobs = jobs.filter(JobExecution::isFailed);
        } else if ("success".equalsIgnoreCase(status)) {
            jobs = jobs.filter(j -> !j.isFailed());
        }
        boolean desc = "desc".equalsIgnoreCase(sortDir);
        Comparator<JobExecution> comparator = switch (sort != null ? sort : "time") {
            case "duration" -> Comparator.comparingLong(JobExecution::durationMs);
            case "name" -> Comparator.comparing(JobExecution::jobName);
            default -> Comparator.comparing(JobExecution::startTimestamp, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (desc) comparator = comparator.reversed();
        List<JobExecution> result = jobs.sorted(comparator).toList();
        return PaginatedResult.of(result, page, size);
    }

    public Map<String, Object> getFilters(List<JobExecution> executions) {
        var jobNames = executions.stream().map(JobExecution::jobName).distinct().sorted().toList();
        var threads = executions.stream().map(JobExecution::thread).distinct().sorted().toList();
        return Map.of("jobNames", jobNames, "threads", threads);
    }
}
