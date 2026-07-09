package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.auth.Tenant;

import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    void save(Tenant tenant);

    void delete(String id);

    Optional<Tenant> findById(String id);

    Optional<Tenant> findByName(String name);

    List<Tenant> findAll();
}
