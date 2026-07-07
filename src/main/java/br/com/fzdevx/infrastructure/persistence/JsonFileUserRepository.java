package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.User;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JsonFileUserRepository
        extends AbstractJsonFileRepository<User>
        implements UserRepository {

    private static final java.lang.reflect.Type USER_LIST_TYPE =
            new ArrayList<User>() {}.getClass().getGenericSuperclass();

    public JsonFileUserRepository(
            @ConfigProperty(name = "rbac.users.file",
                    defaultValue = "data/users.json") String filePath) {
        super(filePath, USER_LIST_TYPE);
    }

    @Override
    public void save(User user) {
        saveEntity(user, u -> u.getId().equals(user.getId()));
    }

    @Override
    public void delete(String id) {
        deleteEntity(u -> u.getId().equals(id));
    }

    @Override
    public Optional<User> findById(String id) {
        return findFirst(u -> u.getId().equals(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return findFirst(u -> username.equalsIgnoreCase(u.getUsername()));
    }

    @Override
    public List<User> findAll() {
        return findAllEntities();
    }

    @Override
    public long count() {
        return findAllEntities().size();
    }
}
