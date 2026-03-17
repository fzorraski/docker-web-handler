package br.com.fzdevx.infrastructure.persistence;

import io.quarkus.logging.Log;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

// ✦ CLEAN — extracted duplicated JSON file read/write/lock pattern from 3 repository implementations
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
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, jsonb.toJson(entities));
        } catch (IOException e) {
            Log.errorf("Failed to write file %s: %s", filePath, e.getMessage());
        }
    }
}
