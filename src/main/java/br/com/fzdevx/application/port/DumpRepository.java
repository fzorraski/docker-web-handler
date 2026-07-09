package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.DatabaseDump;

import java.util.List;
import java.util.Optional;

public interface DumpRepository {

    void save(DatabaseDump dump);

    /**
     * Atomically mutates the stored dump - concurrent edits of other fields
     * are not clobbered by a stale full-object save. Returns false when the
     * dump no longer exists.
     */
    boolean update(String id, java.util.function.Consumer<DatabaseDump> mutator);

    void delete(String id);

    Optional<DatabaseDump> findById(String id);

    Optional<DatabaseDump> findByMd5Hash(String md5Hash);

    Optional<DatabaseDump> findByOriginalFilename(String originalFilename);

    List<DatabaseDump> findAll();
}
