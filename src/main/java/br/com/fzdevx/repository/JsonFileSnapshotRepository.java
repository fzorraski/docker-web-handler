package br.com.fzdevx.repository;

import br.com.fzdevx.model.DatabaseSnapshot;
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
public class JsonFileSnapshotRepository implements SnapshotRepository {

    private static final Type SNAPSHOT_LIST_TYPE =
            new ArrayList<DatabaseSnapshot>() {}.getClass().getGenericSuperclass();

    private final Path filePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    public JsonFileSnapshotRepository(
            @ConfigProperty(name = "database.snapshot.metadata.file",
                    defaultValue = "data/snapshots-metadata.json") String filePath) {
        this.filePath = Path.of(filePath);
    }

    @Override
    public void save(DatabaseSnapshot snapshot) {
        lock.writeLock().lock();
        try {
            List<DatabaseSnapshot> all = readFromFile();
            all.removeIf(s -> s.getId().equals(snapshot.getId()));
            all.add(snapshot);
            writeToFile(all);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(String id) {
        lock.writeLock().lock();
        try {
            List<DatabaseSnapshot> all = readFromFile();
            if (all.removeIf(s -> s.getId().equals(id))) {
                writeToFile(all);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<DatabaseSnapshot> findById(String id) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(s -> s.getId().equals(id))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<DatabaseSnapshot> findAll() {
        lock.readLock().lock();
        try {
            return readFromFile();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<DatabaseSnapshot> readFromFile() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                return new ArrayList<>();
            }
            List<DatabaseSnapshot> result = jsonb.fromJson(content, SNAPSHOT_LIST_TYPE);
            return new ArrayList<>(result);
        } catch (Exception e) {
            Log.errorf("Failed to read snapshots metadata file %s: %s", filePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeToFile(List<DatabaseSnapshot> snapshots) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, jsonb.toJson(snapshots));
        } catch (IOException e) {
            Log.errorf("Failed to write snapshots metadata file %s: %s", filePath, e.getMessage());
        }
    }
}
