package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.domain.model.ContainerExpiration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Typed(PgExpirationRepository.class)
public class PgExpirationRepository implements ExpirationRepository {

    private static final String SELECT = """
            SELECT short_id, full_container_id, expires_at, repository, database_name,
                   delete_database_on_expiration, deletion_armed_by, tenant_id
            FROM container_expiration
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(ContainerExpiration expiration) {
        jdbc.update("""
                INSERT INTO container_expiration (short_id, full_container_id, expires_at,
                    repository, database_name, delete_database_on_expiration,
                    deletion_armed_by, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (short_id) DO UPDATE SET
                    full_container_id = EXCLUDED.full_container_id,
                    expires_at = EXCLUDED.expires_at,
                    repository = EXCLUDED.repository,
                    database_name = EXCLUDED.database_name,
                    delete_database_on_expiration = EXCLUDED.delete_database_on_expiration,
                    deletion_armed_by = EXCLUDED.deletion_armed_by,
                    tenant_id = EXCLUDED.tenant_id
                """,
                expiration.getShortId(), expiration.getFullContainerId(), expiration.getExpiresAt(),
                expiration.getRepository(), expiration.getDatabaseName(),
                expiration.isDeleteDatabaseOnExpiration(),
                expiration.getDeletionArmedBy(), expiration.getTenantId());
    }

    @Override
    public void delete(String shortId) {
        jdbc.update("DELETE FROM container_expiration WHERE short_id = ?", shortId);
    }

    @Override
    public Optional<ContainerExpiration> findByContainerId(String shortId) {
        return jdbc.queryOne(SELECT + "WHERE short_id = ?", PgExpirationRepository::map, shortId);
    }

    @Override
    public List<ContainerExpiration> findByDatabaseName(String databaseName) {
        return jdbc.query(SELECT + "WHERE database_name = ?", PgExpirationRepository::map, databaseName);
    }

    @Override
    public List<ContainerExpiration> findAll() {
        return jdbc.query(SELECT, PgExpirationRepository::map);
    }

    private static ContainerExpiration map(ResultSet rs) throws SQLException {
        ContainerExpiration expiration = new ContainerExpiration();
        expiration.setShortId(rs.getString("short_id"));
        expiration.setFullContainerId(rs.getString("full_container_id"));
        expiration.setExpiresAt(JdbcSupport.instant(rs, "expires_at"));
        expiration.setRepository(rs.getString("repository"));
        expiration.setDatabaseName(rs.getString("database_name"));
        expiration.setDeleteDatabaseOnExpiration(rs.getBoolean("delete_database_on_expiration"));
        expiration.setDeletionArmedBy(rs.getString("deletion_armed_by"));
        expiration.setTenantId(rs.getString("tenant_id"));
        return expiration;
    }
}
