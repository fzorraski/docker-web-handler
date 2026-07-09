package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RoleRepository;
import br.com.fzdevx.application.port.TenantRepository;
import br.com.fzdevx.application.port.UserRepository;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.User;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Builds an {@link AuthorizationService} for tests: user/role stores are empty,
 * tenants come from the given repository (or none). TenantVisibility and
 * TenantEntitlements read tenants through the AuthorizationService snapshot,
 * so tests stub {@code tenantRepository.findAll()}.
 */
public final class TestAuthorization {

    private TestAuthorization() {
    }

    public static AuthorizationService withTenants(TenantRepository tenantRepository) {
        AuthorizationService service = new AuthorizationService();
        service.userRepository = new EmptyUsers();
        service.roleRepository = new EmptyRoles();
        service.tenantRepository = tenantRepository != null ? tenantRepository : new EmptyTenants();
        return service;
    }

    private static final class EmptyUsers implements UserRepository {
        @Override public void save(User user) { }
        @Override public boolean update(String id, Consumer<User> mutator) { return false; }
        @Override public void delete(String id) { }
        @Override public Optional<User> findById(String id) { return Optional.empty(); }
        @Override public Optional<User> findByUsername(String username) { return Optional.empty(); }
        @Override public List<User> findAll() { return List.of(); }
        @Override public long count() { return 0; }
    }

    private static final class EmptyRoles implements RoleRepository {
        @Override public void save(Role role) { }
        @Override public boolean update(String id, Consumer<Role> mutator) { return false; }
        @Override public void delete(String id) { }
        @Override public Optional<Role> findById(String id) { return Optional.empty(); }
        @Override public Optional<Role> findByName(String name) { return Optional.empty(); }
        @Override public List<Role> findAll() { return List.of(); }
    }

    private static final class EmptyTenants implements TenantRepository {
        @Override public void save(Tenant tenant) { }
        @Override public boolean update(String id, Consumer<Tenant> mutator) { return false; }
        @Override public void delete(String id) { }
        @Override public Optional<Tenant> findById(String id) { return Optional.empty(); }
        @Override public Optional<Tenant> findByName(String name) { return Optional.empty(); }
        @Override public List<Tenant> findAll() { return List.of(); }
    }
}
