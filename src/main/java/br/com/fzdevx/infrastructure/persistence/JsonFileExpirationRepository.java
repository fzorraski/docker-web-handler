package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.application.port.ExpirationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


@ApplicationScoped
public class JsonFileExpirationRepository
        extends AbstractJsonFileRepository<ContainerExpiration>
        implements ExpirationRepository {

    private static final java.lang.reflect.Type EXPIRATION_LIST_TYPE =
            new ArrayList<ContainerExpiration>() {}.getClass().getGenericSuperclass();

    public JsonFileExpirationRepository(
            @ConfigProperty(name = "expiration.storage.file", defaultValue = "data/expirations.json") String filePath) {
        super(filePath, EXPIRATION_LIST_TYPE);
    }

    @Override
    public void save(ContainerExpiration expiration) {
        saveEntity(expiration, e -> e.getShortId().equals(expiration.getShortId()));
    }

    @Override
    public void delete(String shortId) {
        deleteEntity(e -> e.getShortId().equals(shortId));
    }

    @Override
    public Optional<ContainerExpiration> findByContainerId(String shortId) {
        return findFirst(e -> e.getShortId().equals(shortId));
    }

    @Override
    public List<ContainerExpiration> findByDatabaseName(String databaseName) {
        return findAllMatching(e -> databaseName.equals(e.getDatabaseName()));
    }

    @Override
    public List<ContainerExpiration> findAll() {
        return findAllEntities();
    }
}
