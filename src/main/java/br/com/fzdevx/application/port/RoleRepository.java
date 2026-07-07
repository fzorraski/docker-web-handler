package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.Role;

import java.util.List;
import java.util.Optional;

public interface RoleRepository {

    void save(Role role);

    void delete(String id);

    Optional<Role> findById(String id);

    Optional<Role> findByName(String name);

    List<Role> findAll();
}
