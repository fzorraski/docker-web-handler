package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.DatabaseMigrationRecord;

import java.util.List;
import java.util.Optional;

public interface MigrationRecordRepository {

    /** Upserts by (databaseName, repository). */
    void save(DatabaseMigrationRecord record);

    Optional<DatabaseMigrationRecord> findByDatabaseAndRepository(String databaseName, String repository);

    List<DatabaseMigrationRecord> findAll();
}
