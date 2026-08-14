package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;

import java.util.Optional;

/**
 * Test wiring helpers for {@link DatabaseDeletionPolicy}, which has
 * package-private injected fields. Most controller tests want the legacy
 * pass-through behavior (RBAC off, everything deletable).
 */
public final class TestDeletionPolicy {

    private TestDeletionPolicy() {
    }

    /** Legacy mode: RBAC inactive, every delete allowed. */
    public static DatabaseDeletionPolicy passthrough() {
        return forUser(new CurrentUser(), null);
    }

    /** RBAC mode with the given (already populated) CurrentUser. */
    public static DatabaseDeletionPolicy forUser(CurrentUser currentUser,
                                                 ManagedDatabaseRepository repository) {
        DatabaseDeletionPolicy policy = new DatabaseDeletionPolicy();
        policy.currentUser = currentUser;
        policy.managedDatabaseRepository = repository != null
                ? repository
                : org.mockito.Mockito.mock(ManagedDatabaseRepository.class,
                        invocation -> invocation.getMethod().getReturnType() == Optional.class
                                ? Optional.empty() : null);
        return policy;
    }
}
