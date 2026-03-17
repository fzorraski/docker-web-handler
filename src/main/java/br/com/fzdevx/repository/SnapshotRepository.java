package br.com.fzdevx.repository;

import br.com.fzdevx.model.DatabaseSnapshot;

import java.util.List;
import java.util.Optional;

public interface SnapshotRepository {

    void save(DatabaseSnapshot snapshot);

    void delete(String id);

    Optional<DatabaseSnapshot> findById(String id);

    List<DatabaseSnapshot> findAll();
}
