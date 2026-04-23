package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.PaginatedResult;
import br.com.fzdevx.domain.model.OrphanJob;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class QueryOrphanJobsUseCase {

    public PaginatedResult<OrphanJob> execute(List<OrphanJob> orphanJobs,
                                              String jobName, String thread,
                                              int page, int size) {
        var orphans = orphanJobs.stream();
        if (jobName != null && !jobName.isBlank()) {
            orphans = orphans.filter(o -> jobName.equals(o.jobName()));
        }
        if (thread != null && !thread.isBlank()) {
            orphans = orphans.filter(o -> thread.equals(o.thread()));
        }
        return PaginatedResult.of(orphans.toList(), page, size);
    }
}
