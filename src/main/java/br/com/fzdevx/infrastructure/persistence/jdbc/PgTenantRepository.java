package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.model.auth.Tenant;
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
@Typed(PgTenantRepository.class)
public class PgTenantRepository implements TenantRepository {

    private static final String SELECT = """
            SELECT id, name, description, enabled_repositories, enabled_databases,
                   color, created_at, updated_at
            FROM tenant
            """;

    private static final String UPSERT = """
            INSERT INTO tenant (id, name, description, enabled_repositories, enabled_databases,
                color, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                enabled_repositories = EXCLUDED.enabled_repositories,
                enabled_databases = EXCLUDED.enabled_databases,
                color = EXCLUDED.color,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(Tenant tenant) {
        jdbc.update(UPSERT, upsertParams(tenant));
    }

    @Override
    public boolean update(String id, Consumer<Tenant> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<Tenant> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgTenantRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            Tenant tenant = current.get();
            mutator.accept(tenant);
            jdbc.update(connection, UPSERT, upsertParams(tenant));
            return true;
        });
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM tenant WHERE id = ?", id);
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgTenantRepository::map, id);
    }

    @Override
    public Optional<Tenant> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return jdbc.queryOne(SELECT + "WHERE lower(name) = lower(?)", PgTenantRepository::map, name);
    }

    @Override
    public List<Tenant> findAll() {
        return jdbc.query(SELECT, PgTenantRepository::map);
    }

    private static Object[] upsertParams(Tenant tenant) {
        return new Object[]{
                tenant.getId(), tenant.getName(), tenant.getDescription(),
                JdbcSupport.JsonbValue.of(tenant.getEnabledRepositories()),
                JdbcSupport.JsonbValue.of(tenant.getEnabledDatabases()),
                tenant.getColor(), tenant.getCreatedAt(), tenant.getUpdatedAt()};
    }

    private static Tenant map(ResultSet rs) throws SQLException {
        Tenant tenant = new Tenant();
        tenant.setId(rs.getString("id"));
        tenant.setName(rs.getString("name"));
        tenant.setDescription(rs.getString("description"));
        tenant.setEnabledRepositories(JdbcSupport.fromJson(rs, "enabled_repositories", JdbcSupport.STRING_LIST));
        tenant.setEnabledDatabases(JdbcSupport.fromJson(rs, "enabled_databases", JdbcSupport.STRING_LIST));
        tenant.setColor(rs.getString("color"));
        tenant.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        tenant.setUpdatedAt(JdbcSupport.instant(rs, "updated_at"));
        return tenant;
    }
}
