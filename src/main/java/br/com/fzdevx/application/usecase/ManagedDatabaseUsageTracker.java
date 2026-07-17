package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

/**
 * Centralizes the "mark this managed database as used by the application" flow.
 * Every code path that does meaningful work against a managed database (create
 * container, restore dump, create snapshot, run migration, upgrade) should call
 * {@link #markUsed} so the UI's "idle since" column reflects reality.
 */
@ApplicationScoped
public class ManagedDatabaseUsageTracker {

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    public void markUsed(String repository, String databaseName) {
        markUsed(repository, databaseName, Instant.now());
    }

    public void markUsed(String repository, String databaseName, Instant when) {
        if (repository == null || repository.isBlank()
                || databaseName == null || databaseName.isBlank()) {
            return;
        }
        try {
            // targeted mutation: a stale full-object save here could revert a
            // concurrent protect toggle or description edit
            boolean updated = managedDatabaseRepository.update(repository, databaseName,
                    md -> md.setAppLastUsedAt(when));
            if (!updated) {
                ManagedDatabase md = new ManagedDatabase(repository, databaseName);
                md.setAppLastUsedAt(when);
                managedDatabaseRepository.save(md);
            }
            listManagedDatabasesUseCase.invalidateCache(repository);
        } catch (Exception e) {
            // Non-critical: usage tracking must never break the caller's flow.
            Log.warnf("Failed to mark database '%s/%s' as used: %s",
                    repository, databaseName, e.getMessage());
        }
    }
}
