package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;


@ApplicationScoped
public class CleanupIdleDatabasesUseCase {

    @Inject
    DatabaseService databaseService;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    public int cleanup(String repository, int minDays) {
        List<ManagedDatabaseInfo> databases = listManagedDatabasesUseCase.listDatabases(repository);
        Instant cutoff = Instant.now().minus(minDays, ChronoUnit.DAYS);

        int deleted = 0;
        for (ManagedDatabaseInfo db : databases) {
            if (db.protectedFlag()) {
                continue;
            }

            // "Never used" databases are always eligible — they've been idle since forever
            Instant reference = db.effectiveLastUsedAt();
            boolean eligible = (reference == null) || reference.isBefore(cutoff);

            if (eligible) {
                try {
                    databaseService.dropDatabase(repository, db.name());
                    managedDatabaseRepository.delete(repository, db.name());
                    deleted++;
                    Log.infof("Cleanup: dropped idle database '%s' on repository '%s'.", db.name(), repository);
                } catch (Exception e) {
                    Log.errorf("Cleanup: failed to drop database '%s' on repository '%s': %s",
                            db.name(), repository, e.getMessage());
                }
            }
        }

        return deleted;
    }
}
