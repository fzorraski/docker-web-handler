package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@ApplicationScoped
@Typed(PgUserRepository.class)
public class PgUserRepository implements UserRepository {

    private static final String SELECT = """
            SELECT id, username, password_hash, role_ids, tenant_ids, enabled,
                   created_at, updated_at, last_login_at
            FROM app_user
            """;

    private static final String UPSERT = """
            INSERT INTO app_user (id, username, password_hash, role_ids, tenant_ids, enabled,
                created_at, updated_at, last_login_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                username = EXCLUDED.username,
                password_hash = EXCLUDED.password_hash,
                role_ids = EXCLUDED.role_ids,
                tenant_ids = EXCLUDED.tenant_ids,
                enabled = EXCLUDED.enabled,
                created_at = EXCLUDED.created_at,
                updated_at = EXCLUDED.updated_at,
                last_login_at = EXCLUDED.last_login_at
            """;

    @Inject
    JdbcSupport jdbc;

    @Override
    public void save(User user) {
        jdbc.update(UPSERT, upsertParams(user));
    }

    @Override
    public boolean update(String id, Consumer<User> mutator) {
        return jdbc.inTransaction(connection -> {
            Optional<User> current = jdbc.queryOne(connection,
                    SELECT + "WHERE id = ? FOR UPDATE", PgUserRepository::map, id);
            if (current.isEmpty()) {
                return false;
            }
            User user = current.get();
            mutator.accept(user);
            jdbc.update(connection, UPSERT, upsertParams(user));
            return true;
        });
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }

    @Override
    public Optional<User> findById(String id) {
        return jdbc.queryOne(SELECT + "WHERE id = ?", PgUserRepository::map, id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return jdbc.queryOne(SELECT + "WHERE lower(username) = lower(?)", PgUserRepository::map, username);
    }

    @Override
    public List<User> findAll() {
        return jdbc.query(SELECT, PgUserRepository::map);
    }

    @Override
    public long count() {
        return jdbc.queryOne("SELECT count(*) FROM app_user", rs -> rs.getLong(1)).orElse(0L);
    }

    private static Object[] upsertParams(User user) {
        return new Object[]{
                user.getId(), user.getUsername(), user.getPasswordHash(),
                JdbcSupport.JsonbValue.of(user.getRoleIds()), JdbcSupport.JsonbValue.of(user.getTenantIds()),
                user.isEnabled(), user.getCreatedAt(), user.getUpdatedAt(), user.getLastLoginAt()};
    }

    private static User map(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getString("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRoleIds(JdbcSupport.fromJson(rs, "role_ids", JdbcSupport.STRING_LIST));
        user.setTenantIds(JdbcSupport.fromJson(rs, "tenant_ids", JdbcSupport.STRING_LIST));
        user.setEnabled(rs.getBoolean("enabled"));
        user.setCreatedAt(JdbcSupport.instant(rs, "created_at"));
        user.setUpdatedAt(JdbcSupport.instant(rs, "updated_at"));
        user.setLastLoginAt(JdbcSupport.instant(rs, "last_login_at"));
        return user;
    }
}
