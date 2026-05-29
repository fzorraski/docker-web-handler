package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.ManagedDatabase;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public interface ManagedDatabaseRepository {

    void save(ManagedDatabase db);

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
