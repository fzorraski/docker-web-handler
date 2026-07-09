package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@ApplicationScoped
@jakarta.enterprise.inject.Typed(JsonFileManagedDatabaseRepository.class)
public class JsonFileManagedDatabaseRepository
        extends AbstractJsonFileRepository<ManagedDatabase>
        implements ManagedDatabaseRepository {

    private static final java.lang.reflect.Type LIST_TYPE =
            new ArrayList<ManagedDatabase>() {}.getClass().getGenericSuperclass();

    public JsonFileManagedDatabaseRepository(
            @ConfigProperty(name = "database.managed.metadata.file",
                    defaultValue = "data/managed-databases.json") String filePath) {
        super(filePath, LIST_TYPE);
    }

    // PostgreSQL folds unquoted identifiers to lowercase, so pg_stat_activity
    // returns the canonical (lowercase) datname even when the user typed the
    // name with mixed case at container-create time. Matching identifiers
    // case-insensitively across all lookups prevents the same physical database
    // from getting two ManagedDatabase records (e.g. "MyDB" from a user flow
    // and "mydb" from the activity poller).
    private static boolean sameId(ManagedDatabase existing, String repository, String name) {
        return existing.getRepository().equalsIgnoreCase(repository)
                && existing.getName().equalsIgnoreCase(name);
    }

    @Override
    public void save(ManagedDatabase db) {
        saveEntity(db, existing -> sameId(existing, db.getRepository(), db.getName()));
    }

    @Override
    public void delete(String repository, String name) {
        deleteEntity(db -> sameId(db, repository, name));
    }

    @Override
    public Optional<ManagedDatabase> find(String repository, String name) {
        return findFirst(db -> sameId(db, repository, name));
    }

    @Override
    public List<ManagedDatabase> findByRepository(String repository) {
        return findAllMatching(db -> db.getRepository().equalsIgnoreCase(repository));
    }

    @Override
    public List<ManagedDatabase> findAll() {
        return findAllEntities();
    }

    @Override
    public void bulkMarkUsed(String repository, Map<String, Instant> updates) {
        if (repository == null || updates == null || updates.isEmpty()) {
            return;
        }
        // Single read + mutate + write under one write lock.
        updateAll(all -> {
            // Index existing records by lowercase name so a poll for "mydb"
            // updates an inline-created "MyDB" record instead of inserting a
            // duplicate.
            Map<String, ManagedDatabase> existingForRepo = new HashMap<>();
            for (ManagedDatabase md : all) {
                if (repository.equalsIgnoreCase(md.getRepository())) {
                    existingForRepo.put(md.getName().toLowerCase(java.util.Locale.ROOT), md);
                }
            }
            boolean anyChange = false;
            for (Map.Entry<String, Instant> entry : updates.entrySet()) {
                String name = entry.getKey();
                Instant proposed = entry.getValue();
                if (name == null || name.isBlank() || proposed == null) continue;

                ManagedDatabase md = existingForRepo.get(name.toLowerCase(java.util.Locale.ROOT));
                if (md == null) {
                    md = new ManagedDatabase(repository, name);
                    md.setAppLastUsedAt(proposed);
                    all.add(md);
                    anyChange = true;
                } else {
                    // Preserve the existing record's case (don't replace
                    // user-typed "MyDB" with PG's "mydb").
                    Instant current = md.getAppLastUsedAt();
                    if (current == null || proposed.isAfter(current)) {
                        md.setAppLastUsedAt(proposed);
                        anyChange = true;
                    }
                }
            }
            return anyChange;
        });
    }
}
