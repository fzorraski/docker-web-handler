package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.domain.model.auth.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
@jakarta.enterprise.inject.Typed(JsonFileTenantRepository.class)
public class JsonFileTenantRepository
        extends AbstractJsonFileRepository<Tenant>
        implements TenantRepository {

    private static final java.lang.reflect.Type TENANT_LIST_TYPE =
            new ArrayList<Tenant>() {}.getClass().getGenericSuperclass();

    public JsonFileTenantRepository(
            @ConfigProperty(name = "rbac.tenants.file",
                    defaultValue = "data/tenants.json") String filePath) {
        super(filePath, TENANT_LIST_TYPE);
    }

    @Override
    public void save(Tenant tenant) {
        saveEntity(tenant, t -> t.getId().equals(tenant.getId()));
    }

    @Override
    public boolean update(String id, java.util.function.Consumer<Tenant> mutator) {
        return updateEntity(t -> t.getId().equals(id), mutator);
    }

    @Override
    public void delete(String id) {
        deleteEntity(t -> t.getId().equals(id));
    }

    @Override
    public Optional<Tenant> findById(String id) {
        return findFirst(t -> t.getId().equals(id));
    }

    @Override
    public Optional<Tenant> findByName(String name) {
        if (name == null) return Optional.empty();
        return findFirst(t -> name.equalsIgnoreCase(t.getName()));
    }

    @Override
    public List<Tenant> findAll() {
        return findAllEntities();
    }
}
