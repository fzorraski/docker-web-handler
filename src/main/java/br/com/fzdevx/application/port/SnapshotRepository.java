package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.DatabaseSnapshot;

import java.util.List;
import java.util.Optional;

public interface SnapshotRepository {

    void save(DatabaseSnapshot snapshot);

    /**
     * Atomically mutates the stored snapshot - concurrent edits of other
     * fields are not clobbered by a stale full-object save. Returns false
     * when the snapshot no longer exists.
     */
    boolean update(String id, java.util.function.Consumer<DatabaseSnapshot> mutator);

    void delete(String id);

    Optional<DatabaseSnapshot> findById(String id);

    List<DatabaseSnapshot> findAll();
}
