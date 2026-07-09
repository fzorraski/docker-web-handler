package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.DumpRepository;
import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.application.port.MigrationRecordRepository;
import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.ScheduleRepository;
import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.application.port.SnapshotRepository;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgDumpRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgExpirationRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgMigrationRecordRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgRoleRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgScheduleRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgSettingsRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgSnapshotRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgTenantRepository;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgUserRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Selects the persistence backend per port: "postgres" (JDBC repositories,
 * schema managed by Flyway) or "file" (legacy JSON files under data/, kept
 * as a fallback for one release). Both implementation sets are regular beans
 * restricted to their concrete type (@Typed), so these producers are the only
 * providers of the port interfaces and injection points stay unchanged.
 */
@ApplicationScoped
public class PersistenceBackendProducer {

    public static final String POSTGRES = "postgres";
    public static final String FILE = "file";

    @Inject
    @ConfigProperty(name = "persistence.backend", defaultValue = FILE)
    String backend;

    void validate(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        if (!POSTGRES.equals(backend) && !FILE.equals(backend)) {
            throw new IllegalStateException(
                    "Invalid persistence.backend '" + backend + "' - use 'postgres' or 'file'.");
        }
        Log.infof("Persistence backend: %s", backend);
        if (FILE.equals(backend)) {
            Log.info("File persistence backend is active. To run without any PostgreSQL, "
                    + "also set APP_DB_ACTIVE=false (deactivates the datasource and Flyway).");
        }
    }

    public boolean isPostgres() {
        return POSTGRES.equals(backend);
    }

    @Produces
    @ApplicationScoped
    SettingsRepository settingsRepository(JsonFileSettingsRepository file, PgSettingsRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    ExpirationRepository expirationRepository(JsonFileExpirationRepository file, PgExpirationRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    MigrationRecordRepository migrationRecordRepository(JsonFileMigrationRepository file,
                                                        PgMigrationRecordRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    UserRepository userRepository(JsonFileUserRepository file, PgUserRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    TenantRepository tenantRepository(JsonFileTenantRepository file, PgTenantRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    RoleRepository roleRepository(JsonFileRoleRepository file, PgRoleRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    ScheduleRepository scheduleRepository(JsonFileScheduleRepository file, PgScheduleRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    ManagedDatabaseRepository managedDatabaseRepository(JsonFileManagedDatabaseRepository file,
                                                        PgManagedDatabaseRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    DumpRepository dumpRepository(JsonFileDumpRepository file, PgDumpRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    SnapshotRepository snapshotRepository(JsonFileSnapshotRepository file, PgSnapshotRepository pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    br.com.fzdevx.application.port.AuditLogger auditLogger(
            FileAuditLogger file, br.com.fzdevx.infrastructure.persistence.jdbc.PgAuditLogger pg) {
        return isPostgres() ? pg : file;
    }

    @Produces
    @ApplicationScoped
    br.com.fzdevx.application.port.SessionRepository sessionRepository(
            InMemorySessionRepository memory, br.com.fzdevx.infrastructure.persistence.jdbc.PgSessionRepository pg) {
        return isPostgres() ? pg : memory;
    }
}
