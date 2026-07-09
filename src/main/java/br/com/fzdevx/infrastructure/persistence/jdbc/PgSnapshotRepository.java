package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.SnapshotRepository;
import br.com.fzdevx.domain.model.DatabaseSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
@Typed(PgSnapshotRepository.class)
public class PgSnapshotRepository implements SnapshotRepository {

    private static final String SELECT = """
            SELECT id, stored_filename, repository, source_database_name, format, md5_hash,
                   created_at, expires_at, file_size, label, container_name, description,
                   last_used_at, temporary, created_by, tenant_id, shared_with_tenants
            FROM database_snapshot
            """;

    private static final String UPSERT = """
            INSERT INTO database_snapshot (id, stored_filename, repository, source_database_name,
                format, md5_hash, created_at, expires_at, file_size, label, container_name,
                description, last_used_at, temporary, created_by, tenant_id, shared_with_tenants)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                stored_filename = EXCLUDED.stored_filename,
                repository = EXCLUDED.repository,
                source_database_name = EXCLUDED.source_database_name,
                format = EXCLUDED.format,
                md5_hash = EXCLUDED.md5_hash,
                created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at,
                file_size = EXCLUDED.file_size,
                label = EXCLUDED.label,
                container_name = EXCLUDED.container_name,
                description = EXCLUDED.description,
                last_used_at = EXCLUDED.last_used_at,
                temporary = EXCLUDED.temporary,
                created_by = EXCLUDED.created_by,
                tenant_id = EXCLUDED.tenant_id,
                shared_with_tenants = EXCLUDED.shared_with_tenants
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(DatabaseSnapshot snapshot) {
        jdbc.update(UPSERT, upsertParams(snapshot));
    }

    @Override
    public boolean update(String id, Consumer<DatabaseSnapshot> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<DatabaseSnapshot> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgSnapshotRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            DatabaseSnapshot snapshot = current.get();
            mutator.accept(snapshot);
            jdbc.update(connection, UPSERT, upsertParams(snapshot));
            return true;
        });
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM database_snapshot WHERE id = ?", id);
    }

    @Override
    public Optional<DatabaseSnapshot> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgSnapshotRepository::map, id);
    }

    @Override
    public List<DatabaseSnapshot> findAll() {
        return jdbc.query(SELECT, PgSnapshotRepository::map);
    }

    private static Object[] upsertParams(DatabaseSnapshot snapshot) {
        return new Object[]{
                snapshot.getId(), snapshot.getStoredFilename(), snapshot.getRepository(),
                snapshot.getSourceDatabaseName(),
                snapshot.getFormat() == null ? null : snapshot.getFormat().name(),
                snapshot.getMd5Hash(), snapshot.getCreatedAt(), snapshot.getExpiresAt(),
                snapshot.getFileSize(), snapshot.getLabel(), snapshot.getContainerName(),
                snapshot.getDescription(), snapshot.getLastUsedAt(), snapshot.isTemporary(),
                snapshot.getCreatedBy(), snapshot.getTenantId(),
                JdbcSupport.JsonbValue.of(snapshot.getSharedWithTenants())};
    }

    private static DatabaseSnapshot map(ResultSet rs) throws SQLException {
        DatabaseSnapshot snapshot = new DatabaseSnapshot();
        snapshot.setId(rs.getString("id"));
        snapshot.setStoredFilename(rs.getString("stored_filename"));
        snapshot.setRepository(rs.getString("repository"));
        snapshot.setSourceDatabaseName(rs.getString("source_database_name"));
        String format = rs.getString("format");
        snapshot.setFormat(format == null ? null : DatabaseSnapshot.Format.valueOf(format));
        snapshot.setMd5Hash(rs.getString("md5_hash"));
        snapshot.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        snapshot.setExpiresAt(JdbcSupport.instant(rs, "expires_at"));
        snapshot.setFileSize(rs.getLong("file_size"));
        snapshot.setLabel(rs.getString("label"));
        snapshot.setContainerName(rs.getString("container_name"));
        snapshot.setDescription(rs.getString("description"));
        snapshot.setLastUsedAt(JdbcSupport.instant(rs, "last_used_at"));
        snapshot.setTemporary(rs.getBoolean("temporary"));
        snapshot.setCreatedBy(rs.getString("created_by"));
        snapshot.setTenantId(rs.getString("tenant_id"));
        snapshot.setSharedWithTenants(JdbcSupport.fromJson(rs, "shared_with_tenants", JdbcSupport.STRING_LIST));
        return snapshot;
    }
}
