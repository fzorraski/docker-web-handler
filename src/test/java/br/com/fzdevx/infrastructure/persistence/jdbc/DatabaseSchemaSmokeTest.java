package br.com.fzdevx.infrastructure.persistence.jdbc;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the application against the Dev Services PostgreSQL container and
 * verifies the Flyway baseline created the schema.
 */
@QuarkusTest
class DatabaseSchemaSmokeTest {

    @Inject
    JdbcSupport jdbc;

    @Test
    void flywayBaselineCreatesAllTables() {
        List<String> tables = jdbc.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                rs -> rs.getString(1));

        for (String expected : List.of(
                "app_user", "tenant", "role", "container_schedule", "managed_database",
                "database_dump", "database_snapshot", "container_expiration",
                "database_migration", "runtime_settings", "audit_log", "auth_session",
                "resource_counter", "image_usage", "json_import_history",
                "user_activity_daily", "activity_summary_state")) {
            assertTrue(tables.contains(expected), "missing table: " + expected);
        }
    }

    @Test
    void uniqueViolationMapsToDuplicateEntityException() {
        jdbc.update("INSERT INTO resource_counter (counter_key, counter_value) VALUES (?, ?)", "smoke", 1L);
        try {
            jdbc.update("INSERT INTO resource_counter (counter_key, counter_value) VALUES (?, ?)", "smoke", 2L);
            throw new AssertionError("expected DuplicateEntityException");
        } catch (br.com.fzdevx.domain.exception.DuplicateEntityException expected) {
            // mapped from SQLState 23505
        } finally {
            jdbc.update("DELETE FROM resource_counter WHERE counter_key = ?", "smoke");
        }
    }
}
