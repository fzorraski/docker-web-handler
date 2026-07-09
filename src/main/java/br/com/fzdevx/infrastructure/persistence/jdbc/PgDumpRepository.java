package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.DumpRepository;
import br.com.fzdevx.domain.model.DatabaseDump;
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
@Typed(PgDumpRepository.class)
public class PgDumpRepository implements DumpRepository {

    private static final String SELECT = """
            SELECT id, original_filename, stored_filename, database_name, version, md5_hash,
                   uploaded_at, expires_at, file_size, format, description, last_used_at,
                   created_by, tenant_id, shared_with_tenants
            FROM database_dump
            """;

    private static final String UPSERT = """
            INSERT INTO database_dump (id, original_filename, stored_filename, database_name, version,
                md5_hash, uploaded_at, expires_at, file_size, format, description, last_used_at,
                created_by, tenant_id, shared_with_tenants)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                original_filename = EXCLUDED.original_filename,
                stored_filename = EXCLUDED.stored_filename,
                database_name = EXCLUDED.database_name,
                version = EXCLUDED.version,
                md5_hash = EXCLUDED.md5_hash,
                uploaded_at = EXCLUDED.uploaded_at,
                expires_at = EXCLUDED.expires_at,
                file_size = EXCLUDED.file_size,
                format = EXCLUDED.format,
                description = EXCLUDED.description,
                last_used_at = EXCLUDED.last_used_at,
                created_by = EXCLUDED.created_by,
                tenant_id = EXCLUDED.tenant_id,
                shared_with_tenants = EXCLUDED.shared_with_tenants
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(DatabaseDump dump) {
        jdbc.update(UPSERT, upsertParams(dump));
    }

    @Override
    public boolean update(String id, Consumer<DatabaseDump> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<DatabaseDump> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgDumpRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            DatabaseDump dump = current.get();
            mutator.accept(dump);
            jdbc.update(connection, UPSERT, upsertParams(dump));
            return true;
        });
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM database_dump WHERE id = ?", id);
    }

    @Override
    public Optional<DatabaseDump> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgDumpRepository::map, id);
    }

    @Override
    public Optional<DatabaseDump> findByMd5Hash(String md5Hash) {
        if (md5Hash == null) {
            return Optional.empty();
        }
        return jdbc.queryOne(SELECT + "WHERE md5_hash = ?", PgDumpRepository::map, md5Hash);
    }

    @Override
    public Optional<DatabaseDump> findByOriginalFilename(String originalFilename) {
        if (originalFilename == null) {
            return Optional.empty();
        }
        return jdbc.queryOne(SELECT + "WHERE original_filename = ?", PgDumpRepository::map, originalFilename);
    }

    @Override
    public List<DatabaseDump> findAll() {
        return jdbc.query(SELECT, PgDumpRepository::map);
    }

    private static Object[] upsertParams(DatabaseDump dump) {
        return new Object[]{
                dump.getId(), dump.getOriginalFilename(), dump.getStoredFilename(),
                dump.getDatabaseName(), dump.getVersion(), dump.getMd5Hash(),
                dump.getUploadedAt(), dump.getExpiresAt(), dump.getFileSize(),
                dump.getFormat() == null ? null : dump.getFormat().name(),
                dump.getDescription(), dump.getLastUsedAt(), dump.getCreatedBy(),
                dump.getTenantId(), JdbcSupport.JsonbValue.of(dump.getSharedWithTenants())};
    }

    private static DatabaseDump map(ResultSet rs) throws SQLException {
        DatabaseDump dump = new DatabaseDump();
        dump.setId(rs.getString("id"));
        dump.setOriginalFilename(rs.getString("original_filename"));
        dump.setStoredFilename(rs.getString("stored_filename"));
        dump.setDatabaseName(rs.getString("database_name"));
        dump.setVersion(rs.getString("version"));
        dump.setMd5Hash(rs.getString("md5_hash"));
        dump.setUploadedAt(JdbcSupport.instant(rs, "uploaded_at"));
        dump.setExpiresAt(JdbcSupport.instant(rs, "expires_at"));
        dump.setFileSize(rs.getLong("file_size"));
        String format = rs.getString("format");
        dump.setFormat(format == null ? null : DatabaseDump.Format.valueOf(format));
        dump.setDescription(rs.getString("description"));
        dump.setLastUsedAt(JdbcSupport.instant(rs, "last_used_at"));
        dump.setCreatedBy(rs.getString("created_by"));
        dump.setTenantId(rs.getString("tenant_id"));
        dump.setSharedWithTenants(JdbcSupport.fromJson(rs, "shared_with_tenants", JdbcSupport.STRING_LIST));
        return dump;
    }
}
