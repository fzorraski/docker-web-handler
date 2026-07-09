package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.domain.exception.DuplicateEntityException;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared plumbing for the PostgreSQL repositories: parameter binding
 * (including jsonb and Instant), row mapping, transactions for
 * select-for-update mutators, and translation of unique-constraint
 * violations (SQLState 23505) into the domain's DuplicateEntityException
 * so the existing GlobalExceptionMapper handles races the same way the
 * use-case pre-checks do.
 */
@ApplicationScoped
public class JdbcSupport {

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * Resolved lazily so the file persistence backend can run with the
     * datasource deactivated (QUARKUS_DATASOURCE_ACTIVE=false, no PostgreSQL
     * available at all).
     */
    @Inject
    jakarta.enterprise.inject.Instance<AgroalDataSource> dataSourceInstance;

    private AgroalDataSource dataSource() {
        return dataSourceInstance.get();
    }

    /** Shared JSON-B instance for jsonb column (de)serialization. */
    public static final Jsonb JSONB = JsonbBuilder.create();

    /** Shared type token for jsonb columns holding a list of strings. */
    public static final java.lang.reflect.Type STRING_LIST =
            new ArrayList<String>() {}.getClass().getGenericSuperclass();

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Marker wrapper so a String parameter is bound as a jsonb value. */
    public record JsonbValue(String json) {
        public static JsonbValue of(Object value) {
            return value == null ? null : new JsonbValue(JSONB.toJson(value));
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = dataSource().getConnection()) {
            return query(connection, sql, mapper, params);
        } catch (SQLException e) {
            throw translate(sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = query(sql, mapper, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int update(String sql, Object... params) {
        try (Connection connection = dataSource().getConnection()) {
            return update(connection, sql, params);
        } catch (SQLException e) {
            throw translate(sql, e);
        }
    }

    /**
     * Runs the given work in one transaction (used by the select-for-update
     * mutators). Rolls back on any exception; the returned connection state
     * is restored before going back to the pool.
     */
    public <T> T inTransaction(ConnectionWork<T> work) {
        try (Connection connection = dataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sql) {
                    throw translate("transaction", sql);
                }
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw translate("transaction", e);
        }
    }

    // ---- connection-scoped variants for use inside inTransaction ----

    public <T> List<T> query(Connection connection, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
                return results;
            }
        }
    }

    public <T> Optional<T> queryOne(Connection connection, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        List<T> results = query(connection, sql, mapper, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    // ---- value conversion helpers ----

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Reads a jsonb column into the given type; null column stays null. */
    public static <T> T fromJson(ResultSet rs, String column, java.lang.reflect.Type type) throws SQLException {
        String json = rs.getString(column);
        return json == null ? null : JSONB.fromJson(json, type);
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object param = params[i];
            switch (param) {
                case null -> statement.setObject(i + 1, null);
                case JsonbValue jsonb -> statement.setObject(i + 1, jsonb.json(), Types.OTHER);
                case Instant instantValue -> statement.setTimestamp(i + 1, Timestamp.from(instantValue));
                default -> statement.setObject(i + 1, param);
            }
        }
    }

    private static RuntimeException translate(String sql, SQLException e) {
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return new DuplicateEntityException("The entity already exists.");
        }
        return new IllegalStateException("Database error executing: " + sql, e);
    }
}
