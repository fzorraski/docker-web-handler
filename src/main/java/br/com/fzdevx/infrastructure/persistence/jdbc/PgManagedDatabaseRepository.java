package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Managed database metadata. Identity is (repository, name), matched
 * case-insensitively like the JSON repository (ux_managed_database_ci).
 */
@ApplicationScoped
@Typed(PgManagedDatabaseRepository.class)
public class PgManagedDatabaseRepository implements ManagedDatabaseRepository {

    private static final String SELECT = """
            SELECT repository, name, protected_flag, app_last_used_at, created_at, description,
                   last_restored_from, last_restored_at, created_by, tenant_id
            FROM managed_database
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(ManagedDatabase db) {
        // atomic upsert keyed on the case-insensitive unique index: concurrent
        // saves of the same database can never collide (last write wins, like
        // the JSON repo's write-locked upsert); the stored casing of
        // repository/name keeps the first writer's form
        jdbc.update("""
                INSERT INTO managed_database (repository, name, protected_flag, app_last_used_at,
                    created_at, description, last_restored_from, last_restored_at, created_by, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (lower(repository), lower(name)) DO UPDATE SET
                    protected_flag = EXCLUDED.protected_flag,
                    app_last_used_at = EXCLUDED.app_last_used_at,
                    created_at = EXCLUDED.created_at,
                    description = EXCLUDED.description,
                    last_restored_from = EXCLUDED.last_restored_from,
                    last_restored_at = EXCLUDED.last_restored_at,
                    created_by = EXCLUDED.created_by,
                    tenant_id = EXCLUDED.tenant_id
                """,
                db.getRepository(), db.getName(), db.isProtectedFlag(), db.getAppLastUsedAt(),
                db.getCreatedAt(), db.getDescription(), db.getLastRestoredFrom(),
                db.getLastRestoredAt(), db.getCreatedBy(), db.getTenantId());
    }

    @Override
    public void delete(String repository, String name) {
        jdbc.update("DELETE FROM managed_database WHERE lower(repository) = lower(?) AND lower(name) = lower(?)",
                repository, name);
    }

    @Override
    public Optional<ManagedDatabase> find(String repository, String name) {
        return jdbc.queryOne(SELECT + "WHERE lower(repository) = lower(?) AND lower(name) = lower(?)",
                PgManagedDatabaseRepository::map, repository, name);
    }

    @Override
    public List<ManagedDatabase> findByRepository(String repository) {
        return jdbc.query(SELECT + "WHERE lower(repository) = lower(?)",
                PgManagedDatabaseRepository::map, repository);
    }

    @Override
    public List<ManagedDatabase> findAll() {
        return jdbc.query(SELECT, PgManagedDatabaseRepository::map);
    }

    @Override
    public void bulkMarkUsed(String repository, Map<String, Instant> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        // One set-based statement per poller cycle instead of one round trip
        // per database. The CI-index upsert creates missing records and bumps
        // timestamps; the WHERE clause never lets a timestamp regress.
        // collapse case-variants to one row per CI identity (keeping the max
        // timestamp) - ON CONFLICT cannot affect the same row twice in one statement
        Map<String, Map.Entry<String, Instant>> byLowerName = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Instant> entry : updates.entrySet()) {
            byLowerName.merge(entry.getKey().toLowerCase(), entry,
                    (a, b) -> a.getValue().isAfter(b.getValue()) ? a : b);
        }
        String[] names = new String[byLowerName.size()];
        java.sql.Timestamp[] timestamps = new java.sql.Timestamp[byLowerName.size()];
        int i = 0;
        for (Map.Entry<String, Instant> entry : byLowerName.values()) {
            names[i] = entry.getKey();
            timestamps[i] = java.sql.Timestamp.from(entry.getValue());
            i++;
        }
        jdbc.inTransaction(connection -> {
            java.sql.Array nameArray = connection.createArrayOf("text", names);
            java.sql.Array tsArray = connection.createArrayOf("timestamptz", timestamps);
            jdbc.update(connection, """
                    INSERT INTO managed_database (repository, name, app_last_used_at)
                    SELECT ?, v.name, v.used_at
                    FROM unnest(?, ?) AS v(name, used_at)
                    ON CONFLICT (lower(repository), lower(name)) DO UPDATE
                    SET app_last_used_at = EXCLUDED.app_last_used_at
                    WHERE managed_database.app_last_used_at IS NULL
                       OR managed_database.app_last_used_at < EXCLUDED.app_last_used_at
                    """, repository, nameArray, tsArray);
            return null;
        });
    }

    private static ManagedDatabase map(ResultSet rs) throws SQLException {
        ManagedDatabase db = new ManagedDatabase();
        db.setRepository(rs.getString("repository"));
        db.setName(rs.getString("name"));
        db.setProtectedFlag(rs.getBoolean("protected_flag"));
        db.setAppLastUsedAt(JdbcSupport.instant(rs, "app_last_used_at"));
        db.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        db.setDescription(rs.getString("description"));
        db.setLastRestoredFrom(rs.getString("last_restored_from"));
        db.setLastRestoredAt(JdbcSupport.instant(rs, "last_restored_at"));
        db.setCreatedBy(rs.getString("created_by"));
        db.setTenantId(rs.getString("tenant_id"));
        return db;
    }
}
