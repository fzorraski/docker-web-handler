package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.MigrationRecordRepository;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Typed(PgMigrationRecordRepository.class)
public class PgMigrationRecordRepository implements MigrationRecordRepository {

    private static final String SELECT = """
            SELECT database_name, repository, source_version, target_version,
                   versions_included, total_statements, mode, migrated_at, migrated_by
            FROM database_migration
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(DatabaseMigrationRecord record) {
        jdbc.update("""
                INSERT INTO database_migration (database_name, repository, source_version,
                    target_version, versions_included, total_statements, mode, migrated_at, migrated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (database_name, repository) DO UPDATE SET
                    source_version = EXCLUDED.source_version,
                    target_version = EXCLUDED.target_version,
                    versions_included = EXCLUDED.versions_included,
                    total_statements = EXCLUDED.total_statements,
                    mode = EXCLUDED.mode,
                    migrated_at = EXCLUDED.migrated_at,
                    migrated_by = EXCLUDED.migrated_by
                """,
                record.getDatabaseName(), record.getRepository(), record.getSourceVersion(),
                record.getTargetVersion(), JdbcSupport.JsonbValue.of(record.getVersionsIncluded()),
                record.getTotalStatements(), record.getMode(), record.getMigratedAt(),
                record.getMigratedBy());
    }

    @Override
    public Optional<DatabaseMigrationRecord> findByDatabaseAndRepository(String databaseName, String repository) {
        return jdbc.queryOne(SELECT + "WHERE database_name = ? AND repository = ?",
                PgMigrationRecordRepository::map, databaseName, repository);
    }

    @Override
    public List<DatabaseMigrationRecord> findAll() {
        return jdbc.query(SELECT, PgMigrationRecordRepository::map);
    }

    private static DatabaseMigrationRecord map(ResultSet rs) throws SQLException {
        DatabaseMigrationRecord record = new DatabaseMigrationRecord();
        record.setDatabaseName(rs.getString("database_name"));
        record.setRepository(rs.getString("repository"));
        record.setSourceVersion(rs.getString("source_version"));
        record.setTargetVersion(rs.getString("target_version"));
        record.setVersionsIncluded(JdbcSupport.fromJson(rs, "versions_included", JdbcSupport.STRING_LIST));
        record.setTotalStatements(rs.getObject("total_statements", Integer.class));
        record.setMode(rs.getString("mode"));
        record.setMigratedAt(JdbcSupport.instant(rs, "migrated_at"));
        record.setMigratedBy(rs.getString("migrated_by"));
        return record;
    }
}
