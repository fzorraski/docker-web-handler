package br.com.fzdevx.repository;

import br.com.fzdevx.model.ContainerExpiration;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@ApplicationScoped
public class JsonFileExpirationRepository implements ExpirationRepository {

    private static final Type EXPIRATION_LIST_TYPE =
            new ArrayList<ContainerExpiration>() {}.getClass().getGenericSuperclass();

    private final Path filePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    public JsonFileExpirationRepository(
            @ConfigProperty(name = "expiration.storage.file", defaultValue = "data/expirations.json") String filePath) {
        this.filePath = Path.of(filePath);
    }

    @Override
    public void save(ContainerExpiration expiration) {
        lock.writeLock().lock();
        try {
            List<ContainerExpiration> all = readFromFile();
            all.removeIf(e -> e.getShortId().equals(expiration.getShortId()));
            all.add(expiration);
            writeToFile(all);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(String shortId) {
        lock.writeLock().lock();
        try {
            List<ContainerExpiration> all = readFromFile();
            if (all.removeIf(e -> e.getShortId().equals(shortId))) {
                writeToFile(all);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<ContainerExpiration> findByContainerId(String shortId) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(e -> e.getShortId().equals(shortId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ContainerExpiration> findByDatabaseName(String databaseName) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(e -> databaseName.equals(e.getDatabaseName()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ContainerExpiration> findAll() {
        lock.readLock().lock();
        try {
            return readFromFile();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<ContainerExpiration> readFromFile() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                return new ArrayList<>();
            }
            List<ContainerExpiration> result = jsonb.fromJson(content, EXPIRATION_LIST_TYPE);
            return new ArrayList<>(result);
        } catch (Exception e) {
            Log.errorf("Failed to read expirations file %s: %s", filePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeToFile(List<ContainerExpiration> expirations) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, jsonb.toJson(expirations));
        } catch (IOException e) {
            Log.errorf("Failed to write expirations file %s: %s", filePath, e.getMessage());
        }
    }
}
