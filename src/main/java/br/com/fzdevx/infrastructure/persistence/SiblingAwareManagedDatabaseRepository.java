package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.ManagedDatabaseMerger;
import br.com.fzdevx.infrastructure.config.RepositorySiblingResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Makes managed-database identity follow the physical database: a lookup or write through
 * one repository sees and updates the row any sibling repository (same PostgreSQL server)
 * already holds. Rows keep the repository that first wrote them; a new row is created under
 * the acting repository only when no sibling has one.
 *
 * <p>Sibling membership considers the allowed repositories plus every repository that still
 * holds rows and has a PG host, so dropping a repository from the allowed list does not
 * orphan the metadata it consolidated. Writes are serialized per server in this JVM, and a
 * write that meets several rows for one database folds them first, so reads and writes can
 * never disagree about a protection flag.</p>
 */
public class SiblingAwareManagedDatabaseRepository implements ManagedDatabaseRepository {

    private static final long KNOWN_REPOSITORIES_TTL_MS = 30_000;

    private final ManagedDatabaseRepository delegate;
    private final RepositorySiblingResolver siblings;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile Set<String> knownRepositories = Set.of();
    private volatile long knownRepositoriesAt = Long.MIN_VALUE;

    public SiblingAwareManagedDatabaseRepository(ManagedDatabaseRepository delegate, RepositorySiblingResolver siblings) {
        this.delegate = delegate;
        this.siblings = siblings;
    }

    @Override
    public void save(ManagedDatabase db) {
        if (db == null || db.getRepository() == null || db.getName() == null) {
            delegate.save(db);
            return;
        }
        ReentrantLock lock = lockFor(db.getRepository());
        lock.lock();
        try {
            List<ManagedDatabase> existing = delegate.findCandidates(siblingsOf(db.getRepository()), db.getName());
            if (existing.isEmpty()) {
                delegate.save(db);
                knownRepositoriesAt = Long.MIN_VALUE;
                return;
            }
            // Every caller reaches save() to create a missing record; when a sibling already
            // holds one, folding into it is strictly safer than a second row or an overwrite.
            ManagedDatabase target = consolidate(existing);
            delegate.update(target.getRepository(), target.getName(), stored -> ManagedDatabaseMerger.mergeInto(stored, db));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean update(String repository, String name, Consumer<ManagedDatabase> mutator) {
        List<String> group = siblingsOf(repository);
        if (group.isEmpty() || name == null) return false;
        ReentrantLock lock = lockFor(repository);
        lock.lock();
        try {
            List<ManagedDatabase> existing = delegate.findCandidates(group, name);
            if (existing.isEmpty()) return false;
            ManagedDatabase target = consolidate(existing);
            return delegate.update(target.getRepository(), target.getName(), mutator);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String repository, String name) {
        // One physical database: its metadata goes for every repository that can see it.
        for (String sibling : siblingsOf(repository)) {
            delegate.delete(sibling, name);
        }
    }

    @Override
    public Optional<ManagedDatabase> find(String repository, String name) {
        List<String> group = siblingsOf(repository);
        if (group.isEmpty() || name == null) return Optional.empty();
        List<ManagedDatabase> rows = delegate.findCandidates(group, name);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(rows.size() == 1 ? rows.getFirst() : ManagedDatabaseMerger.merge(rows));
    }

    @Override
    public List<ManagedDatabase> findByRepository(String repository) {
        Map<String, List<ManagedDatabase>> byName = new LinkedHashMap<>();
        for (ManagedDatabase row : delegate.findByRepositories(siblingsOf(repository))) {
            byName.computeIfAbsent(row.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
        }
        List<ManagedDatabase> result = new ArrayList<>(byName.size());
        for (List<ManagedDatabase> rows : byName.values()) {
            result.add(rows.size() == 1 ? rows.getFirst() : ManagedDatabaseMerger.merge(rows));
        }
        return result;
    }

    @Override
    public List<ManagedDatabase> findByRepositories(Collection<String> repositories) {
        return delegate.findByRepositories(repositories);
    }

    @Override
    public List<ManagedDatabase> findCandidates(Collection<String> repositories, String name) {
        return delegate.findCandidates(repositories, name);
    }

    @Override
    public List<ManagedDatabase> findAll() {
        return delegate.findAll();
    }

    @Override
    public void bulkMarkUsed(String repository, Map<String, Instant> updates) {
        if (repository == null || updates == null || updates.isEmpty()) return;
        ReentrantLock lock = lockFor(repository);
        lock.lock();
        try {
            // Route each name to the repository whose row already exists; unknown names are
            // created under the acting repository, as before.
            Map<String, String> repositoryByName = new LinkedHashMap<>();
            for (ManagedDatabase row : delegate.findByRepositories(siblingsOf(repository))) {
                repositoryByName.putIfAbsent(row.getName().toLowerCase(Locale.ROOT), row.getRepository());
            }
            Map<String, Map<String, Instant>> byTarget = new LinkedHashMap<>();
            for (Map.Entry<String, Instant> entry : updates.entrySet()) {
                if (entry.getKey() == null) continue;
                String target = repositoryByName.getOrDefault(entry.getKey().toLowerCase(Locale.ROOT), repository);
                byTarget.computeIfAbsent(target, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Map<String, Instant>> part : byTarget.entrySet()) {
                delegate.bulkMarkUsed(part.getKey(), part.getValue());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Folds several rows for one database into the winner row and removes the others; returns the surviving row. */
    private ManagedDatabase consolidate(List<ManagedDatabase> rows) {
        if (rows.size() == 1) return rows.getFirst();
        ManagedDatabase merged = ManagedDatabaseMerger.merge(rows);
        delegate.save(merged);
        for (ManagedDatabase row : rows) {
            if (!sameKey(row, merged)) delegate.delete(row.getRepository(), row.getName());
        }
        return merged;
    }

    private static boolean sameKey(ManagedDatabase a, ManagedDatabase b) {
        return a.getRepository().equalsIgnoreCase(b.getRepository()) && a.getName().equalsIgnoreCase(b.getName());
    }

    private List<String> siblingsOf(String repository) {
        return siblings.siblings(repository, knownRepositories());
    }

    /** Repositories that hold rows, refreshed every 30 s and whenever a row lands under a new one. */
    private Set<String> knownRepositories() {
        long now = System.currentTimeMillis();
        if (now - knownRepositoriesAt > KNOWN_REPOSITORIES_TTL_MS) {
            knownRepositories = delegate.findAll().stream()
                    .map(ManagedDatabase::getRepository)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            knownRepositoriesAt = now;
        }
        return knownRepositories;
    }

    private ReentrantLock lockFor(String repository) {
        String key = siblings.serverKey(repository).orElse("repo:" + (repository == null ? "" : repository.toLowerCase(Locale.ROOT)));
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}
