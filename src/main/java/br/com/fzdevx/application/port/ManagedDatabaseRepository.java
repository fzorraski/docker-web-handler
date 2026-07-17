package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ManagedDatabase;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public interface ManagedDatabaseRepository {

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

    List<ManagedDatabase> findAll();

    /**
     * Updates {@code appLastUsedAt} for many databases of a repository under a
     * single write lock + single file rewrite. Entries whose timestamp would
     * regress (existing &gt;= proposed) are skipped. Missing records are created.
     * No-op if {@code updates} is empty.
     */
    void bulkMarkUsed(String repository, Map<String, Instant> updates);
}
