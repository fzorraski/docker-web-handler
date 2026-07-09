package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.Role;

import java.util.List;
import java.util.Optional;

public interface RoleRepository {

    void save(Role role);

    /**
     * Atomically mutates the stored role - concurrent edits of other fields
     * are not clobbered by a stale full-object save. Returns false when the
     * role no longer exists.
     */
    boolean update(String id, java.util.function.Consumer<Role> mutator);

    void delete(String id);

    Optional<Role> findById(String id);

    Optional<Role> findByName(String name);

    List<Role> findAll();
}
