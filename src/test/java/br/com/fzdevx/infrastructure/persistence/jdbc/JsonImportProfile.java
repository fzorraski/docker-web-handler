package br.com.fzdevx.infrastructure.persistence.jdbc;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Boots the app with the postgres backend, the JSON importer enabled, and
 * legacy JSON files seeded into a scratch directory under target/.
 */
public class JsonImportProfile implements QuarkusTestProfile {

    static final Path DATA_DIR = Path.of("target", "json-import-test-data");

    @Override
    public Map<String, String> getConfigOverrides() {
        seedFiles();
        // every store path is redirected into the scratch dir - the importer
        // must NEVER see the developer's real data/ directory from a test
        return Map.ofEntries(
                Map.entry("persistence.backend", "postgres"),
                Map.entry("json.import.enabled", "true"),
                Map.entry("rbac.roles.file", DATA_DIR.resolve("roles.json").toString()),
                Map.entry("rbac.tenants.file", DATA_DIR.resolve("tenants.json").toString()),
                Map.entry("rbac.users.file", DATA_DIR.resolve("users.json").toString()),
                Map.entry("rbac.settings.file", DATA_DIR.resolve("settings.json").toString()),
                Map.entry("expiration.storage.file", DATA_DIR.resolve("expirations.json").toString()),
                Map.entry("resource.counters.file", DATA_DIR.resolve("resource-counters.json").toString()),
                Map.entry("image.usage.file", DATA_DIR.resolve("image-usage.json").toString()),
                Map.entry("database.managed.metadata.file", DATA_DIR.resolve("managed-databases.json").toString()),
                Map.entry("database.dump.metadata.file", DATA_DIR.resolve("dumps-metadata.json").toString()),
                Map.entry("database.snapshot.metadata.file", DATA_DIR.resolve("snapshots-metadata.json").toString()),
                Map.entry("schedule.storage.file", DATA_DIR.resolve("schedules.json").toString()),
                Map.entry("database.migration.storage.file", DATA_DIR.resolve("migrations.json").toString()));
    }

    private static void seedFiles() {
        try {
            if (Files.exists(DATA_DIR)) {
                try (var paths = Files.walk(DATA_DIR)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            Files.createDirectories(DATA_DIR);
            Files.writeString(DATA_DIR.resolve("roles.json"), """
                    [{"id":"role-import-1","name":"Importers","description":"imported role",
                      "permissions":["CONTAINERS_VIEW","CONTAINERS_RUN"],"builtIn":false,
                      "createdAt":"2026-01-01T00:00:00Z"}]
                    """);
            Files.writeString(DATA_DIR.resolve("tenants.json"), """
                    [{"id":"tenant-import-1","name":"Imported Squad","description":null,
                      "enabledRepositories":["repo-a"],
                      "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]
                    """);
            Files.writeString(DATA_DIR.resolve("users.json"), """
                    [{"id":"user-import-1","username":"imported-admin","passwordHash":"$2a$10$hash",
                      "roleIds":["role-import-1"],"tenantIds":["tenant-import-1"],"enabled":true,
                      "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]
                    """);
            Files.writeString(DATA_DIR.resolve("settings.json"),
                    "{\"logAnalyzerEnabled\":true,\"terminalMaxSessions\":7}");
            Files.writeString(DATA_DIR.resolve("expirations.json"), """
                    [{"shortId":"abc123import","fullContainerId":"abc123importfull",
                      "expiresAt":"2030-01-01T00:00:00Z","repository":"repo-a",
                      "databaseName":"db1","deleteDatabaseOnExpiration":true}]
                    """);
            Files.writeString(DATA_DIR.resolve("resource-counters.json"),
                    "{\"containers\":42,\"restores\":7,\"_startedAt\":\"2026-01-01T00:00:00Z\"}");
            Files.writeString(DATA_DIR.resolve("image-usage.json"),
                    "{\"sha256:imported\":\"2026-02-01T00:00:00Z\"}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
