package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@ApplicationScoped
public class JsonFileManagedDatabaseRepository
        extends AbstractJsonFileRepository<ManagedDatabase>
        implements ManagedDatabaseRepository {

    private static final java.lang.reflect.Type LIST_TYPE =
            new ArrayList<ManagedDatabase>() {}.getClass().getGenericSuperclass();

    public JsonFileManagedDatabaseRepository(
            @ConfigProperty(name = "database.managed.metadata.file",
                    defaultValue = "data/managed-databases.json") String filePath) {
        super(filePath, LIST_TYPE);
    }

    @Override
    public void save(ManagedDatabase db) {
        saveEntity(db, existing ->
                existing.getRepository().equals(db.getRepository())
                        && existing.getName().equals(db.getName()));
    }

    @Override
    public void delete(String repository, String name) {
        deleteEntity(db -> db.getRepository().equals(repository) && db.getName().equals(name));
    }

    @Override
    public Optional<ManagedDatabase> find(String repository, String name) {
        return findFirst(db -> db.getRepository().equals(repository) && db.getName().equals(name));
    }

    @Override
    public List<ManagedDatabase> findByRepository(String repository) {
        return findAllMatching(db -> db.getRepository().equals(repository));
    }

    @Override
    public List<ManagedDatabase> findAll() {
        return findAllEntities();
    }
}
