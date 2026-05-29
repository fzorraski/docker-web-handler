package br.com.fzdevx.infrastructure.persistence;

import io.quarkus.logging.Log;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;


public abstract class AbstractJsonFileRepository<T> {

    private Path filePath;
    private Type listType;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    protected final Jsonb jsonb = JsonbBuilder.create();

    // CDI proxy requires a no-args constructor
    protected AbstractJsonFileRepository() {}

    protected AbstractJsonFileRepository(String filePath, Type listType) {
        this.filePath = Path.of(filePath);
        this.listType = listType;
    }

    protected void saveEntity(T entity, Predicate<T> idMatcher) {
        lock.writeLock().lock();
        try {
            List<T> all = readFromFile();
            all.removeIf(idMatcher);
            all.add(entity);
            writeToFile(all);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reads, mutates, and writes the whole collection under a single write lock.
     * Use for bulk updates where N individual {@link #saveEntity} calls would mean
     * N file rewrites; this collapses them to one. The mutator returns true if it
     * actually changed anything (so we can skip the write when there is nothing
     * to persist).
     */
    protected void updateAll(Predicate<List<T>> mutator) {
        lock.writeLock().lock();
        try {
            List<T> all = readFromFile();
            if (mutator.test(all)) {
                writeToFile(all);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected boolean deleteEntity(Predicate<T> idMatcher) {
        lock.writeLock().lock();
        try {
            List<T> all = readFromFile();
            if (all.removeIf(idMatcher)) {
                writeToFile(all);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected Optional<T> findFirst(Predicate<T> filter) {
        lock.readLock().lock();
        try {
            return readFromFile().stream().filter(filter).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    protected List<T> findAllMatching(Predicate<T> filter) {
        lock.readLock().lock();
        try {
            return readFromFile().stream().filter(filter).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    protected List<T> findAllEntities() {
        lock.readLock().lock();
        try {
            return readFromFile();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<T> readFromFile() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                return new ArrayList<>();
            }
            List<T> result = jsonb.fromJson(content, listType);
            return new ArrayList<>(result);
        } catch (Exception e) {
            Log.errorf("Failed to read file %s: %s", filePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeToFile(List<T> entities) {
        // Write to a sibling temp file then atomically rename. A JVM crash, OOM,
        // SIGKILL, or container restart mid-write can never leave the target
        // file truncated or corrupted — readers always see either the previous
        // complete contents or the new complete contents.
        Path tmp = null;
        try {
            Files.createDirectories(filePath.getParent());
            tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp." + System.nanoTime());
            Files.writeString(tmp, jsonb.toJson(entities));
            try {
                Files.move(tmp, filePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicNotSupported) {
                // Some filesystems (e.g., older NFS) don't support ATOMIC_MOVE
                // — fall back to a regular replace. Less safe but better than
                // never writing at all.
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null; // moved away — don't delete in finally.
        } catch (IOException e) {
            Log.errorf("Failed to write file %s: %s", filePath, e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }
}
