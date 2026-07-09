package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.domain.exception.DuplicateEntityException;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.domain.model.auth.Role;
import br.com.fzdevx.domain.model.auth.Tenant;
import br.com.fzdevx.domain.model.auth.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PostgresBackendProfile.class)
class PgIdentityRepositoriesTest {

    @Inject
    PgUserRepository users;

    @Inject
    PgTenantRepository tenants;

    @Inject
    PgRoleRepository roles;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- round trips ----

    @Test
    void user_roundTripWithJsonbLists() {
        User user = new User(unique("alice"), "hash", List.of("role-1", "role-2"));
        user.setTenantIds(List.of("t1"));
        users.save(user);

        User loaded = users.findById(user.getId()).orElseThrow();
        assertEquals(List.of("role-1", "role-2"), loaded.getRoleIds());
        assertEquals(List.of("t1"), loaded.getTenantIds());
        assertTrue(loaded.isEnabled());

        // case-insensitive lookup
        assertTrue(users.findByUsername(user.getUsername().toUpperCase()).isPresent());

        users.delete(user.getId());
        assertTrue(users.findById(user.getId()).isEmpty());
    }

    @Test
    void tenant_roundTripNullVsEmptyEntitlements() {
        Tenant tenant = new Tenant(unique("Squad"), "desc");
        tenant.setEnabledRepositories(List.of());
        tenants.save(tenant);

        Tenant loaded = tenants.findById(tenant.getId()).orElseThrow();
        assertEquals(List.of(), loaded.getEnabledRepositories());
        assertNull(loaded.getEnabledDatabases());
        assertTrue(tenants.findByName(loaded.getName().toUpperCase()).isPresent());
        tenants.delete(tenant.getId());
    }

    @Test
    void role_permissionEnumSetRoundTrip() {
        Role role = new Role(unique("Deployers"), null,
                Set.of(Permission.CONTAINERS_RUN, Permission.CONTAINERS_VIEW));
        roles.save(role);

        Role loaded = roles.findById(role.getId()).orElseThrow();
        assertEquals(Set.of(Permission.CONTAINERS_RUN, Permission.CONTAINERS_VIEW),
                Set.copyOf(loaded.getPermissions()));
        roles.delete(role.getId());
    }

    // ---- uniqueness under concurrency ----

    @Test
    void parallelSameUsernameCreates_exactlyOneWins() throws Exception {
        String username = unique("race");
        int attempts = 6;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                // different case per attempt - the lower(username) index must still collide
                String candidate = i % 2 == 0 ? username : username.toUpperCase();
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        users.save(new User(candidate, "hash", "role-1"));
                        successes.incrementAndGet();
                    } catch (DuplicateEntityException e) {
                        duplicates.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, successes.get(), "exactly one create must win");
        assertEquals(attempts - 1, duplicates.get());
        users.findByUsername(username).ifPresent(u -> users.delete(u.getId()));
    }

    // ---- atomic mutators ----

    @Test
    void parallelFieldEdits_bothSurvive() throws Exception {
        User user = new User(unique("bob"), "hash", "role-1");
        users.save(user);

        Thread renameRoles = new Thread(() ->
                users.update(user.getId(), u -> u.setRoleIds(List.of("role-9"))));
        Thread disable = new Thread(() ->
                users.update(user.getId(), u -> u.setEnabled(false)));
        renameRoles.start();
        disable.start();
        renameRoles.join();
        disable.join();

        User loaded = users.findById(user.getId()).orElseThrow();
        assertEquals(List.of("role-9"), loaded.getRoleIds());
        assertFalse(loaded.isEnabled());
        users.delete(user.getId());
    }

    @Test
    void update_returnsFalseForMissingId() {
        assertFalse(users.update("missing-id", u -> u.setEnabled(false)));
        assertFalse(tenants.update("missing-id", t -> t.setName("x")));
        assertFalse(roles.update("missing-id", r -> r.setName("x")));
    }
}
