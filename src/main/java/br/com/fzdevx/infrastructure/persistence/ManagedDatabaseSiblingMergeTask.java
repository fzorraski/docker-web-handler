package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.ManagedDatabaseMerger;
import br.com.fzdevx.infrastructure.config.RepositorySiblingResolver;
import br.com.fzdevx.infrastructure.persistence.jdbc.PgManagedDatabaseRepository;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One-time, idempotent merge of metadata rows that forked before sibling repositories
 * shared identity: for every database that has a row under more than one repository of
 * the same PostgreSQL server, the rows are folded by {@link ManagedDatabaseMerger}, the
 * winner is written first and the losers removed afterwards, so a crash in between only
 * leaves duplicates for the next boot to fold again.
 *
 * <p>Runs on the raw backend on purpose: the sibling-aware decorator would delete the
 * winner along with the losers.</p>
 */
@ApplicationScoped
public class ManagedDatabaseSiblingMergeTask {

    @Inject
    PersistenceBackendProducer backendProducer;

    @Inject
    JsonFileManagedDatabaseRepository fileRepository;

    @Inject
    PgManagedDatabaseRepository pgRepository;

    @Inject
    RepositorySiblingResolver siblingResolver;

    @Inject
    @ConfigProperty(name = "database.managed.sibling-merge-at-startup", defaultValue = "true")
    boolean enabled;

    /** Priority 2: after the JSON import (1) and before the timers that read the store. */
    void onStartup(@Observes @Priority(2) StartupEvent event) {
        if (!enabled) {
            Log.info("Managed databases: sibling merge at startup is disabled.");
            return;
        }
        try {
            mergeDuplicates(backendProducer.isPostgres() ? pgRepository : fileRepository);
        } catch (Exception e) {
            Log.warnf("Managed databases: sibling merge failed: %s", e.getMessage());
        }
    }

    /**
     * Folds duplicate rows across each sibling group. Visible for tests.
     *
     * @return the number of rows removed
     */
    int mergeDuplicates(ManagedDatabaseRepository raw) {
        List<ManagedDatabase> all = raw.findAll();
        // repositories that hold rows but left the allowed list still take part, so their
        // rows are folded rather than orphaned
        Set<String> stored = all.stream().map(ManagedDatabase::getRepository)
                .filter(r -> r != null && !r.isBlank()).collect(Collectors.toSet());
        Map<String, List<String>> groups = siblingResolver.siblingGroups(stored).entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (groups.isEmpty()) return 0;

        int removed = 0;
        List<String> summary = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            Set<String> members = group.getValue().stream().map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            Map<String, List<ManagedDatabase>> byName = new LinkedHashMap<>();
            for (ManagedDatabase row : all) {
                if (row.getRepository() != null && row.getName() != null
                        && members.contains(row.getRepository().toLowerCase(Locale.ROOT))) {
                    byName.computeIfAbsent(row.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
                }
            }
            int removedInGroup = 0;
            for (List<ManagedDatabase> bucket : byName.values()) {
                if (bucket.size() < 2) continue;
                try {
                    ManagedDatabase merged = ManagedDatabaseMerger.merge(bucket);
                    raw.save(merged);
                    for (ManagedDatabase loser : bucket) {
                        if (loser.getRepository().equalsIgnoreCase(merged.getRepository())) continue;
                        raw.delete(loser.getRepository(), loser.getName());
                        removedInGroup++;
                    }
                    Log.debugf("Managed databases: merged '%s' into repository '%s' (%s)", merged.getName(),
                            merged.getRepository(), merged.getCreatedBy() != null ? "creator known" : "oldest record");
                } catch (Exception e) {
                    Log.warnf("Managed databases: could not merge '%s' across %s: %s",
                            bucket.getFirst().getName(), group.getValue(), e.getMessage());
                }
            }
            if (removedInGroup > 0) {
                summary.add(String.join("+", group.getValue()) + " -> " + removedInGroup);
            }
            removed += removedInGroup;
        }
        if (removed > 0) {
            Log.infof("Managed databases: merged %d duplicate record(s) across sibling repositories (%s).",
                    removed, String.join("; ", summary));
        }
        return removed;
    }
}
