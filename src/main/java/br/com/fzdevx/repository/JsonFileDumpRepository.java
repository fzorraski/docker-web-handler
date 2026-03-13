package br.com.fzdevx.repository;

import br.com.fzdevx.model.DatabaseDump;
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
public class JsonFileDumpRepository implements DumpRepository {

    private static final Type DUMP_LIST_TYPE =
            new ArrayList<DatabaseDump>() {}.getClass().getGenericSuperclass();

    private final Path filePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Jsonb jsonb = JsonbBuilder.create();

    public JsonFileDumpRepository(
            @ConfigProperty(name = "database.dump.metadata.file", defaultValue = "data/dumps-metadata.json") String filePath) {
        this.filePath = Path.of(filePath);
    }

    @Override
    public void save(DatabaseDump dump) {
        lock.writeLock().lock();
        try {
            List<DatabaseDump> all = readFromFile();
            all.removeIf(d -> d.getId().equals(dump.getId()));
            all.add(dump);
            writeToFile(all);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(String id) {
        lock.writeLock().lock();
        try {
            List<DatabaseDump> all = readFromFile();
            if (all.removeIf(d -> d.getId().equals(id))) {
                writeToFile(all);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<DatabaseDump> findById(String id) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(d -> d.getId().equals(id))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<DatabaseDump> findByMd5Hash(String md5Hash) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(d -> md5Hash.equals(d.getMd5Hash()))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<DatabaseDump> findByOriginalFilename(String originalFilename) {
        lock.readLock().lock();
        try {
            return readFromFile().stream()
                    .filter(d -> originalFilename.equals(d.getOriginalFilename()))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<DatabaseDump> findAll() {
        lock.readLock().lock();
        try {
            return readFromFile();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<DatabaseDump> readFromFile() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                return new ArrayList<>();
            }
            List<DatabaseDump> result = jsonb.fromJson(content, DUMP_LIST_TYPE);
            return new ArrayList<>(result);
        } catch (Exception e) {
            Log.errorf("Failed to read dumps metadata file %s: %s", filePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeToFile(List<DatabaseDump> dumps) {
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, jsonb.toJson(dumps));
        } catch (IOException e) {
            Log.errorf("Failed to write dumps metadata file %s: %s", filePath, e.getMessage());
        }
    }
}
