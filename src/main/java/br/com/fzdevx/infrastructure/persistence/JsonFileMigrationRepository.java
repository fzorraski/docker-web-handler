package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JsonFileMigrationRepository extends AbstractJsonFileRepository<DatabaseMigrationRecord> {

    private static final java.lang.reflect.Type LIST_TYPE =
            new ArrayList<DatabaseMigrationRecord>() {}.getClass().getGenericSuperclass();

    public JsonFileMigrationRepository(
            @ConfigProperty(name = "database.migration.storage.file", defaultValue = "data/migrations.json") String filePath) {
        super(filePath, LIST_TYPE);
    }

    public void save(DatabaseMigrationRecord record) {
        saveEntity(record, r -> r.getDatabaseName().equals(record.getDatabaseName())
                && r.getRepository().equals(record.getRepository()));
    }

    public Optional<DatabaseMigrationRecord> findByDatabaseAndRepository(String databaseName, String repository) {
        return findFirst(r -> r.getDatabaseName().equals(databaseName)
                && r.getRepository().equals(repository));
    }

    public List<DatabaseMigrationRecord> findAll() {
        return findAllEntities();
    }
}
