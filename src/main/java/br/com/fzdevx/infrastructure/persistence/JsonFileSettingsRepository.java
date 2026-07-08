package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.RuntimeSettings;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Single-object variant of the JSON file repository pattern: stores one
 * {@link RuntimeSettings} object with the same locking and crash-safe
 * (atomic tmp-file move) write strategy as {@link AbstractJsonFileRepository}.
 */
@ApplicationScoped
public class JsonFileSettingsRepository implements SettingsRepository {

    private final Jsonb jsonb = JsonbBuilder.create();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path filePath;

    JsonFileSettingsRepository() {
        this.filePath = null; // CDI proxy constructor
    }

    @Inject
    public JsonFileSettingsRepository(
            @ConfigProperty(name = "rbac.settings.file",
                    defaultValue = "data/settings.json") String filePath) {
        this.filePath = Paths.get(filePath);
    }

    @Override
    public RuntimeSettings get() {
        lock.readLock().lock();
        try {
            if (!Files.exists(filePath)) {
                return new RuntimeSettings();
            }
            String json = Files.readString(filePath);
            if (json.isBlank()) {
                return new RuntimeSettings();
            }
            return jsonb.fromJson(json, RuntimeSettings.class);
        } catch (Exception e) {
            Log.errorf(e, "Failed to read settings file %s - using defaults.", filePath);
            return new RuntimeSettings();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void save(RuntimeSettings settings) {
        lock.writeLock().lock();
        try {
            AtomicFileWriter.write(filePath, jsonb.toJson(settings));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write settings file " + filePath, e);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
