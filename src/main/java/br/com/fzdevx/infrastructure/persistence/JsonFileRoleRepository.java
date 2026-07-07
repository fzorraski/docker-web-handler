package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.domain.model.auth.Role;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JsonFileRoleRepository
        extends AbstractJsonFileRepository<Role>
        implements RoleRepository {

    private static final java.lang.reflect.Type ROLE_LIST_TYPE =
            new ArrayList<Role>() {}.getClass().getGenericSuperclass();

    public JsonFileRoleRepository(
            @ConfigProperty(name = "rbac.roles.file",
                    defaultValue = "data/roles.json") String filePath) {
        super(filePath, ROLE_LIST_TYPE);
    }

    @Override
    public void save(Role role) {
        saveEntity(role, r -> r.getId().equals(role.getId()));
    }

    @Override
    public void delete(String id) {
        deleteEntity(r -> r.getId().equals(id));
    }

    @Override
    public Optional<Role> findById(String id) {
        return findFirst(r -> r.getId().equals(id));
    }

    @Override
    public Optional<Role> findByName(String name) {
        if (name == null) return Optional.empty();
        return findFirst(r -> name.equalsIgnoreCase(r.getName()));
    }

    @Override
    public List<Role> findAll() {
        return findAllEntities();
    }
}
