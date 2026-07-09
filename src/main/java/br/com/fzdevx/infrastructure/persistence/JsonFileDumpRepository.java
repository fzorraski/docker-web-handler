package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.application.port.DumpRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@ApplicationScoped
public class JsonFileDumpRepository
        extends AbstractJsonFileRepository<DatabaseDump>
        implements DumpRepository {

    private static final java.lang.reflect.Type DUMP_LIST_TYPE =
            new ArrayList<DatabaseDump>() {}.getClass().getGenericSuperclass();

    public JsonFileDumpRepository(
            @ConfigProperty(name = "database.dump.metadata.file", defaultValue = "data/dumps-metadata.json") String filePath) {
        super(filePath, DUMP_LIST_TYPE);
    }

    @Override
    public void save(DatabaseDump dump) {
        saveEntity(dump, d -> d.getId().equals(dump.getId()));
    }

    @Override
    public boolean update(String id, java.util.function.Consumer<DatabaseDump> mutator) {
        return updateEntity(d -> d.getId().equals(id), mutator);
    }

    @Override
    public void delete(String id) {
        deleteEntity(d -> d.getId().equals(id));
    }

    @Override
    public Optional<DatabaseDump> findById(String id) {
        return findFirst(d -> d.getId().equals(id));
    }

    @Override
    public Optional<DatabaseDump> findByMd5Hash(String md5Hash) {
        return findFirst(d -> md5Hash.equals(d.getMd5Hash()));
    }

    @Override
    public Optional<DatabaseDump> findByOriginalFilename(String originalFilename) {
        return findFirst(d -> originalFilename.equals(d.getOriginalFilename()));
    }

    @Override
    public List<DatabaseDump> findAll() {
        return findAllEntities();
    }
}
