package br.com.fzdevx.infrastructure.persistence.jdbc;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(JsonImportProfile.class)
class JsonDataImporterTest {

    @Inject
    PgUserRepository users;

    @Inject
    PgTenantRepository tenants;

    @Inject
    PgRoleRepository roles;

    @Inject
    PgSettingsRepository settings;


    @Inject
    JsonDataImporter importer;

    @Inject
    JdbcSupport jdbc;

    @Test
    void importedAllSeededStores() {
        var user = users.findByUsername("imported-admin").orElseThrow();
        assertEquals(List.of("role-import-1"), user.getRoleIds());
        assertEquals(List.of("tenant-import-1"), user.getTenantIds());

        var tenant = tenants.findById("tenant-import-1").orElseThrow();
        assertEquals(List.of("repo-a"), tenant.getEnabledRepositories());
        assertNull(tenant.getEnabledDatabases());

        var role = roles.findById("role-import-1").orElseThrow();
        assertEquals(2, role.getPermissions().size());

        assertEquals(true, settings.get().getLogAnalyzerEnabled());
        assertEquals(7, settings.get().getTerminalMaxSessions());

        // the expiration WAS imported (marker below proves it ran before the
        // expiration service) - the service then correctly removed it as an
        // orphan because no such Docker container exists in this environment
        assertEquals(1L, jdbc.queryOne(
                "SELECT records_imported FROM json_import_history WHERE store = 'expirations'",
                rs -> rs.getLong(1)).orElse(0L));

        assertEquals(42L, jdbc.queryOne(
                "SELECT counter_value FROM resource_counter WHERE counter_key = 'containers'",
                rs -> rs.getLong(1)).orElse(0L));
        assertEquals(1L, jdbc.queryOne(
                "SELECT count(*) FROM image_usage WHERE image_id = 'sha256:imported'",
                rs -> rs.getLong(1)).orElse(0L));
    }

    @Test
    void sourceFilesRenamedAndMarkersRecorded() {
        assertTrue(Files.exists(JsonImportProfile.DATA_DIR.resolve("users.json.imported")));
        assertFalse(Files.exists(JsonImportProfile.DATA_DIR.resolve("users.json")));

        List<String> stores = jdbc.query("SELECT store FROM json_import_history", rs -> rs.getString(1));
        assertTrue(stores.containsAll(List.of(
                "roles", "tenants", "users", "settings", "expirations",
                "resource-counters", "image-usage")));
        // absent files were skipped, no marker written
        assertFalse(stores.contains("dumps"));
    }

    @Test
    void reRunningTheImporterIsANoOp() {
        long usersBefore = jdbc.queryOne("SELECT count(*) FROM app_user", rs -> rs.getLong(1)).orElse(0L);
        importer.onStartup(null);
        long usersAfter = jdbc.queryOne("SELECT count(*) FROM app_user", rs -> rs.getLong(1)).orElse(0L);
        assertEquals(usersBefore, usersAfter);
    }
}
