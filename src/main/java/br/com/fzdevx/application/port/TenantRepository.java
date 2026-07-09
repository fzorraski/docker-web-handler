package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.Tenant;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    void save(Tenant tenant);

    /**
     * Atomically applies field edits to the current persisted state, so
     * concurrent edits of other fields are not overwritten with stale data.
     * Returns false when no tenant with the given id exists.
     */
    boolean update(String id, java.util.function.Consumer<Tenant> mutator);

    void delete(String id);

    Optional<Tenant> findById(String id);

    Optional<Tenant> findByName(String name);

    List<Tenant> findAll();
}
