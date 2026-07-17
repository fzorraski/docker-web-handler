package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.infrastructure.persistence.JsonFileDumpRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileExpirationRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileMigrationRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileRoleRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileScheduleRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileSettingsRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileSnapshotRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileTenantRepository;
import br.com.fzdevx.infrastructure.persistence.JsonFileUserRepository;
import br.com.fzdevx.infrastructure.persistence.PersistenceBackendProducer;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * One-time migration of the legacy JSON files under data/ into PostgreSQL.
 * Runs at startup (before the scheduler/expiration services load their
 * timers) when the postgres backend is active.
 *
 * Crash safety per store: a marker row (records_imported = -1) is claimed
 * BEFORE inserting; only a completed import (records_imported >= 0) counts.
 * A crashed or failed run leaves the in-progress marker, so the next boot
 * wipes the partial rows and retries. A table that has rows but NO marker
 * (e.g. the app was booted once without the data/ volume and the bootstrap
 * seeded it) aborts startup loudly instead of silently stranding legacy data.
 * Records that violate a unique constraint (legacy duplicates the file
 * backend never rejected) are skipped with a warning naming the record.
 * After a successful import the source file is renamed to *.imported.
 */
@ApplicationScoped
public class JsonDataImporter {

    private static final int IN_PROGRESS = -1;

    @Inject
    PersistenceBackendProducer backendProducer;

    @Inject
    @ConfigProperty(name = "json.import.enabled", defaultValue = "true")
    boolean importEnabled;

    // source file paths (same properties/defaults as the JsonFile* repositories)
    @Inject @ConfigProperty(name = "rbac.roles.file", defaultValue = "data/roles.json") String rolesFile;
    @Inject @ConfigProperty(name = "rbac.tenants.file", defaultValue = "data/tenants.json") String tenantsFile;
    @Inject @ConfigProperty(name = "rbac.users.file", defaultValue = "data/users.json") String usersFile;
    @Inject @ConfigProperty(name = "rbac.settings.file", defaultValue = "data/settings.json") String settingsFile;
    @Inject @ConfigProperty(name = "database.managed.metadata.file", defaultValue = "data/managed-databases.json") String managedDbFile;
    @Inject @ConfigProperty(name = "database.dump.metadata.file", defaultValue = "data/dumps-metadata.json") String dumpsFile;
    @Inject @ConfigProperty(name = "database.snapshot.metadata.file", defaultValue = "data/snapshots-metadata.json") String snapshotsFile;
    @Inject @ConfigProperty(name = "schedule.storage.file", defaultValue = "data/schedules.json") String schedulesFile;
    @Inject @ConfigProperty(name = "expiration.storage.file", defaultValue = "data/expirations.json") String expirationsFile;
    @Inject @ConfigProperty(name = "database.migration.storage.file", defaultValue = "data/migrations.json") String migrationsFile;
    @Inject @ConfigProperty(name = "resource.counters.file", defaultValue = "data/resource-counters.json") String countersFile;
    @Inject @ConfigProperty(name = "image.usage.file", defaultValue = "data/image-usage.json") String imageUsageFile;

    // legacy readers (JSON-B binding identical to what wrote the files)
    @Inject JsonFileRoleRepository fileRoles;
    @Inject JsonFileTenantRepository fileTenants;
    @Inject JsonFileUserRepository fileUsers;
    @Inject JsonFileSettingsRepository fileSettings;
    @Inject JsonFileManagedDatabaseRepository fileManagedDbs;
    @Inject JsonFileDumpRepository fileDumps;
    @Inject JsonFileSnapshotRepository fileSnapshots;
    @Inject JsonFileScheduleRepository fileSchedules;
    @Inject JsonFileExpirationRepository fileExpirations;
    @Inject JsonFileMigrationRepository fileMigrations;

    // postgres writers
    @Inject PgRoleRepository pgRoles;
    @Inject PgTenantRepository pgTenants;
    @Inject PgUserRepository pgUsers;
    @Inject PgSettingsRepository pgSettings;
    @Inject PgManagedDatabaseRepository pgManagedDbs;
    @Inject PgDumpRepository pgDumps;
    @Inject PgSnapshotRepository pgSnapshots;
    @Inject PgScheduleRepository pgSchedules;
    @Inject PgExpirationRepository pgExpirations;
    @Inject PgMigrationRecordRepository pgMigrations;

    @Inject
    JdbcSupport jdbc;

    /**
     * Priority 1 so this observer runs before the scheduler/expiration/dump
     * services (default priority) load their in-JVM timers from the store.
     */
    void onStartup(@Observes @Priority(1) StartupEvent event) {
        if (!backendProducer.isPostgres() || !importEnabled) {
            return;
        }
        // dependency order: roles/tenants before users; the rest are independent
        importStore("roles", rolesFile, "role", () -> importList("roles",
                fileRoles.findAll(), pgRoles::save, r -> "role '" + r.getName() + "'"));
        importStore("tenants", tenantsFile, "tenant", () -> importList("tenants",
                fileTenants.findAll(), pgTenants::save, t -> "tenant '" + t.getName() + "'"));
        importStore("users", usersFile, "app_user", () -> importList("users",
                fileUsers.findAll(), pgUsers::save, u -> "user '" + u.getUsername() + "'"));
        importStore("settings", settingsFile, "runtime_settings", () -> {
            RuntimeSettings settings = fileSettings.get();
            pgSettings.save(settings);
            return 1;
        });
        importStore("managed-databases", managedDbFile, "managed_database", () -> importList("managed-databases",
                fileManagedDbs.findAll(), pgManagedDbs::save,
                db -> "database '" + db.getRepository() + "/" + db.getName() + "'"));
        importStore("dumps", dumpsFile, "database_dump", () -> importList("dumps",
                fileDumps.findAll(), pgDumps::save, d -> "dump '" + d.getOriginalFilename() + "'"));
        importStore("snapshots", snapshotsFile, "database_snapshot", () -> importList("snapshots",
                fileSnapshots.findAll(), pgSnapshots::save, s -> "snapshot '" + s.getStoredFilename() + "'"));
        importStore("schedules", schedulesFile, "container_schedule", () -> importList("schedules",
                fileSchedules.findAll(), pgSchedules::save, s -> "schedule '" + s.getName() + "'"));
        importStore("expirations", expirationsFile, "container_expiration", () -> importList("expirations",
                fileExpirations.findAll(), pgExpirations::save, e -> "expiration '" + e.getShortId() + "'"));
        importStore("migrations", migrationsFile, "database_migration", () -> importList("migrations",
                fileMigrations.findAll(), pgMigrations::save,
                m -> "migration '" + m.getDatabaseName() + "/" + m.getRepository() + "'"));
        importStore("resource-counters", countersFile, "resource_counter", this::importCounters);
        importStore("image-usage", imageUsageFile, "image_usage", this::importImageUsage);
    }

    /**
     * Writes every record, skipping legacy duplicates the new unique indexes
     * reject (the file backend never enforced them retroactively) - one bad
     * record must not crash-loop the whole migration.
     */
    private <T> int importList(String store, List<T> records, Consumer<T> writer, Function<T, String> describe) {
        int imported = 0;
        for (T record : records) {
            try {
                writer.accept(record);
                imported++;
            } catch (DuplicateEntityException e) {
                Log.warnf("Import of '%s': skipping %s - it duplicates an already imported record "
                        + "(the legacy file allowed it, the database constraint does not).",
                        store, describe.apply(record));
            }
        }
        return imported;
    }

    private void importStore(String store, String file, String table, IntSupplier work) {
        Long marker = jdbc.queryOne(
                        "SELECT records_imported FROM json_import_history WHERE store = ?",
                        rs -> rs.getLong(1), store)
                .orElse(null);
        if (marker != null && marker >= 0) {
            return; // completed on an earlier boot
        }
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            if (marker != null) {
                // A previous import crashed AND the source file is now gone.
                // Returning silently would let the app run on a half-imported
                // table for weeks and then wipe it once the file reappears.
                throw new IllegalStateException(
                        "Import of '" + store + "' was interrupted on a previous boot and its source file "
                                + file + " is now missing. Restore the file to retry the import, or delete the "
                                + "json_import_history row for '" + store + "' to accept the current table contents.");
            }
            return;
        }
        // fail loudly on an unreadable/corrupt file BEFORE the store repositories
        // silently turn it into an empty list ("successful" import of nothing)
        int sourceEntries = countSourceEntries(store, path);
        if (marker != null) {
            // a previous run crashed mid-import: wipe the partial rows and retry
            Log.warnf("Import of '%s' was interrupted on a previous boot - retrying from scratch.", store);
            jdbc.update("DELETE FROM " + table);
        } else {
            long existing = jdbc.queryOne("SELECT count(*) FROM " + table, rs -> rs.getLong(1)).orElse(0L);
            if (existing > 0) {
                // silently skipping would permanently strand the legacy data
                throw new IllegalStateException(
                        "Refusing to import '" + store + "': table " + table + " already has " + existing
                                + " row(s) but no import marker (was the app booted once without the data/"
                                + " volume?). Either delete those rows to import " + file
                                + ", or move the file away to keep the current database contents.");
            }
            // claim the store before writing; a concurrent instance loses the claim
            int claimed = jdbc.update("""
                    INSERT INTO json_import_history (store, source_file, records_imported)
                    VALUES (?, ?, ?) ON CONFLICT (store) DO NOTHING
                    """, store, file, IN_PROGRESS);
            if (claimed == 0) {
                Log.warnf("Import of '%s' skipped: another instance claimed it.", store);
                return;
            }
        }
        int imported;
        try {
            imported = work.getAsInt();
            // the repositories swallow binding errors into empty lists; a store
            // that visibly holds entries but imported none is a failed import
            if (sourceEntries > 0 && imported == 0) {
                throw new IllegalStateException(
                        file + " contains " + sourceEntries + " entr(y/ies) but none could be read - "
                                + "the file may not match the expected format.");
            }
        } catch (RuntimeException e) {
            // best-effort cleanup; the in-progress marker makes the next boot retry cleanly
            try {
                jdbc.update("DELETE FROM " + table);
            } catch (RuntimeException cleanup) {
                Log.errorf(cleanup, "Cleanup of partially imported table %s failed.", table);
            }
            throw new IllegalStateException(
                    "JSON import of '" + store + "' from " + file + " failed - aborting startup.", e);
        }
        jdbc.update("UPDATE json_import_history SET records_imported = ?, imported_at = now() WHERE store = ?",
                imported, store);
        Log.infof("Imported %d record(s) from %s into %s.", imported, file, table);
        renameImported(path);
    }

    /**
     * Number of top-level entries in the source file. Throws (aborting
     * startup) when the file cannot be read or is not valid JSON - a corrupt
     * users.json must never be "imported" as an empty store and renamed away.
     */
    private int countSourceEntries(String store, Path path) {
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read " + path + " for the '" + store + "' import - fix the file "
                            + "(permissions/corruption) or move it away.", e);
        }
        if (content.isBlank()) {
            return 0;
        }
        try {
            Object parsed = JdbcSupport.JSONB.fromJson(content, Object.class);
            if (parsed instanceof java.util.Collection<?> collection) {
                return collection.size();
            }
            if (parsed instanceof Map<?, ?> map) {
                return map.size();
            }
            return 1;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    path + " is not valid JSON - fix the file or move it away before the '"
                            + store + "' import can run.", e);
        }
    }

    private void renameImported(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".imported"));
        } catch (IOException e) {
            // not fatal: the marker row already prevents a re-import
            Log.warnf("Could not rename %s to *.imported: %s", path, e.getMessage());
        }
    }

    private int importCounters() {
        Map<String, Object> counters = readJsonMap(countersFile);
        int imported = 0;
        for (Map.Entry<String, Object> entry : counters.entrySet()) {
            long value;
            if ("_startedAt".equals(entry.getKey())) {
                value = parseInstantMillis(String.valueOf(entry.getValue()));
            } else if (entry.getValue() instanceof Number number) {
                value = number.longValue();
            } else {
                continue;
            }
            jdbc.update("""
                    INSERT INTO resource_counter (counter_key, counter_value) VALUES (?, ?)
                    ON CONFLICT (counter_key) DO NOTHING
                    """, entry.getKey(), value);
            imported++;
        }
        return imported;
    }

    private int importImageUsage() {
        Map<String, Object> usage = readJsonMap(imageUsageFile);
        int imported = 0;
        for (Map.Entry<String, Object> entry : usage.entrySet()) {
            Instant lastUsed;
            try {
                lastUsed = Instant.parse(String.valueOf(entry.getValue()));
            } catch (Exception e) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO image_usage (image_id, last_used_at) VALUES (?, ?)
                    ON CONFLICT (image_id) DO NOTHING
                    """, entry.getKey(), lastUsed);
            imported++;
        }
        return imported;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String file) {
        try {
            String content = Files.readString(Path.of(file));
            if (content.isBlank()) {
                return Map.of();
            }
            return JdbcSupport.JSONB.fromJson(content, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    private static long parseInstantMillis(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
