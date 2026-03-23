package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.DatabaseSnapshot;
import br.com.fzdevx.application.port.SnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@ApplicationScoped
public class JsonFileSnapshotRepository
        extends AbstractJsonFileRepository<DatabaseSnapshot>
        implements SnapshotRepository {

    private static final java.lang.reflect.Type SNAPSHOT_LIST_TYPE =
            new ArrayList<DatabaseSnapshot>() {}.getClass().getGenericSuperclass();

    public JsonFileSnapshotRepository(
            @ConfigProperty(name = "database.snapshot.metadata.file",
                    defaultValue = "data/snapshots-metadata.json") String filePath) {
        super(filePath, SNAPSHOT_LIST_TYPE);
    }

    @Override
    public void save(DatabaseSnapshot snapshot) {
        saveEntity(snapshot, s -> s.getId().equals(snapshot.getId()));
    }

    @Override
    public void delete(String id) {
        deleteEntity(s -> s.getId().equals(id));
    }

    @Override
    public Optional<DatabaseSnapshot> findById(String id) {
        return findFirst(s -> s.getId().equals(id));
    }

    @Override
    public List<DatabaseSnapshot> findAll() {
        return findAllEntities();
    }
}
