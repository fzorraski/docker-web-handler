package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ApplicationScoped
public class RunMigrationUseCase {

    @Inject
    MigrationService migrationService;

    @Inject
    DatabaseService databaseService;

    private final ConcurrentHashMap<String, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();

    public boolean cancel(String ticket) {
        AtomicBoolean flag = activeRuns.get(ticket);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    public void execute(RunMigrationRequest request, Consumer<ContainerEvent> eventSink, String ticket) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (ticket != null) {
            activeRuns.put(ticket, cancelled);
        }

        try {
            eventSink.accept(ContainerEvent.info("Validating", "Validating migration request..."));

            if (!migrationService.isEnabled()) {
                eventSink.accept(ContainerEvent.error("Validating", "Database migration feature is not enabled."));
                return;
            }

            Optional<String> repoError = InputValidator.validateRepository(request.getRepository());
            if (repoError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", repoError.get()));
                return;
            }

            Optional<String> dbError = InputValidator.validateDatabaseName(request.getTargetDatabase());
            if (dbError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", dbError.get()));
                return;
            }

            Optional<String> modeError = InputValidator.validateMigrationMode(request.getMigrationMode());
            if (modeError.isPresent()) {
                eventSink.accept(ContainerEvent.error("Validating", modeError.get()));
                return;
            }

            if ("MANUAL".equals(request.getMigrationMode())) {
                Optional<String> sqlError = InputValidator.validateMigrationSql(request.getMigrationSql());
                if (sqlError.isPresent()) {
                    eventSink.accept(ContainerEvent.error("Validating", sqlError.get()));
                    return;
                }
            } else if ("API".equals(request.getMigrationMode())) {
                Optional<String> srcError = InputValidator.validateVersion(request.getMigrationSourceVersion());
                if (srcError.isPresent()) {
                    eventSink.accept(ContainerEvent.error("Validating", "Source version: " + srcError.get()));
                    return;
                }
                Optional<String> tgtError = InputValidator.validateVersion(request.getMigrationTargetVersion());
                if (tgtError.isPresent()) {
                    eventSink.accept(ContainerEvent.error("Validating", "Target version: " + tgtError.get()));
                    return;
                }
                if (!migrationService.isApiAvailable(request.getRepository())) {
                    eventSink.accept(ContainerEvent.error("Validating",
                            "No migration API URL configured for repository: " + request.getRepository()));
                    return;
                }
            }

            if (!databaseService.hasDatabaseConfig(request.getRepository())) {
                eventSink.accept(ContainerEvent.error("Validating",
                        "No database configuration found for repository: " + request.getRepository()));
                return;
            }

            eventSink.accept(ContainerEvent.info("Validating", "Validation passed."));

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error("Running Migration", "Migration cancelled."));
                return;
            }

            String pgImage = databaseService.getContainerImage(request.getRepository());
            DatabasePort.PgConnectionInfo pgInfo = databaseService.getConnectionInfo(request.getRepository());

            boolean ok = migrationService.orchestrateMigration(
                    request.getMigrationMode(), request.getMigrationSql(),
                    request.getMigrationSourceVersion(), request.getMigrationTargetVersion(),
                    request.getRepository(), request.getTargetDatabase(), pgImage,
                    pgInfo, eventSink, cancelled);

            if (!ok) {
                return;
            }

            eventSink.accept(ContainerEvent.success("Complete",
                    "Migration completed successfully on database '" + request.getTargetDatabase() + "'."));

        } catch (Exception e) {
            Log.errorf("Standalone migration failed: %s", e.getMessage());
            eventSink.accept(ContainerEvent.error("Running Migration",
                    "Migration failed: " + e.getMessage()));
        } finally {
            if (ticket != null) {
                activeRuns.remove(ticket);
            }
        }
    }
}
