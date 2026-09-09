package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ManagedDatabase;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public interface ManagedDatabaseRepository {

    /**
     * Creates the record, or replaces the one with the same identity. Through the
     * sibling-aware repository the app injects, a database a sibling repository (same
     * PostgreSQL server) already holds is folded into that stored row instead.
     */
    void save(ManagedDatabase db);

    /**
     * Atomically mutates the stored record (matched case-insensitively) -
     * concurrent edits of other fields (protection flag, description, tenant)
     * are not clobbered by a stale full-object save. Returns false when no
     * record exists yet; callers then create one via {@link #save}.
     */
    boolean update(String repository, String name, java.util.function.Consumer<ManagedDatabase> mutator);

    void delete(String repository, String name);

    Optional<ManagedDatabase> find(String repository, String name);

    List<ManagedDatabase> findByRepository(String repository);

    /** Rows of any of the given repositories (repository matched case-insensitively). */
    List<ManagedDatabase> findByRepositories(java.util.Collection<String> repositories);

    /** Rows named {@code name} (case-insensitively) in any of the given repositories. */
    List<ManagedDatabase> findCandidates(java.util.Collection<String> repositories, String name);

    List<ManagedDatabase> findAll();

    /**
     * Updates {@code appLastUsedAt} for many databases of a repository under a
     * single write lock + single file rewrite. Entries whose timestamp would
     * regress (existing &gt;= proposed) are skipped. Missing records are created.
     * No-op if {@code updates} is empty.
     */
    void bulkMarkUsed(String repository, Map<String, Instant> updates);
}
