package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

@ApplicationScoped
@Typed(PgRoleRepository.class)
public class PgRoleRepository implements RoleRepository {

    private static final String SELECT = """
            SELECT id, name, description, permissions, built_in, created_at
            FROM role
            """;

    private static final String UPSERT = """
            INSERT INTO role (id, name, description, permissions, built_in, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                permissions = EXCLUDED.permissions,
                built_in = EXCLUDED.built_in,
                created_at = EXCLUDED.created_at
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(Role role) {
        jdbc.update(UPSERT, upsertParams(role));
    }

    @Override
    public boolean update(String id, Consumer<Role> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<Role> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgRoleRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            Role role = current.get();
            mutator.accept(role);
            jdbc.update(connection, UPSERT, upsertParams(role));
            return true;
        });
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM role WHERE id = ?", id);
    }

    @Override
    public Optional<Role> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgRoleRepository::map, id);
    }

    @Override
    public Optional<Role> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return jdbc.queryOne(SELECT + "WHERE lower(name) = lower(?)", PgRoleRepository::map, name);
    }

    @Override
    public List<Role> findAll() {
        return jdbc.query(SELECT, PgRoleRepository::map);
    }

    private static Object[] upsertParams(Role role) {
        List<String> permissionNames = role.getPermissions().stream().map(Enum::name).toList();
        return new Object[]{
                role.getId(), role.getName(), role.getDescription(),
                JdbcSupport.JsonbValue.of(permissionNames), role.isBuiltIn(), role.getCreatedAt()};
    }

    private static Role map(ResultSet rs) throws SQLException {
        Role role = new Role();
        role.setId(rs.getString("id"));
        role.setName(rs.getString("name"));
        role.setDescription(rs.getString("description"));
        List<String> permissionNames = JdbcSupport.fromJson(rs, "permissions", JdbcSupport.STRING_LIST);
        Set<Permission> permissions = new LinkedHashSet<>();
        if (permissionNames != null) {
            for (String name : permissionNames) {
                // unknown names (removed enum values) are skipped, like JSON-B does
                try {
                    permissions.add(Permission.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        role.setPermissions(permissions);
        role.setBuiltIn(rs.getBoolean("built_in"));
        role.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        return role;
    }
}
