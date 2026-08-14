package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.auth.Permission;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseDeletionPolicyTest {

    private static ManagedDatabase db(String createdBy) {
        ManagedDatabase md = new ManagedDatabase("pg", "mydb");
        md.setCreatedBy(createdBy);
        return md;
    }

    private static DatabaseDeletionPolicy policyFor(CurrentUser user) {
        return TestDeletionPolicy.forUser(user, null);
    }

    private static CurrentUser userWith(Permission... permissions) {
        CurrentUser user = new CurrentUser();
        user.set("u1", "alice", Set.of(permissions));
        return user;
    }

    @Test
    void fullDeletePermission_deletesAnything() {
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_DELETE));

        assertTrue(policy.canDelete(db("someone-else")));
        assertTrue(policy.canDelete((ManagedDatabase) null));
    }

    @Test
    void deleteOwn_deletesOnlyOwnDatabases() {
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_DELETE_OWN));

        assertTrue(policy.canDelete(db("alice")));
        assertFalse(policy.canDelete(db("bob")));
    }

    @Test
    void deleteOwn_matchesUsernameCaseInsensitively() {
        // findByUsername matches case-insensitively, so ownership must too
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_DELETE_OWN));

        assertTrue(policy.canDelete(db("Alice")));
    }

    @Test
    void deleteOwn_refusesDatabasesWithoutARecordedCreator() {
        // pre-stamping databases and missing metadata are nobody's "own"
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_DELETE_OWN));

        assertFalse(policy.canDelete(db(null)));
        assertFalse(policy.canDelete((ManagedDatabase) null));
    }

    @Test
    void noDeletePermissionAtAll_refusesEverything() {
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_VIEW));

        assertFalse(policy.canDelete(db("alice")));
    }

    @Test
    void rbacOff_deletesEverything() {
        // legacy mode: CurrentUser.hasPermission grants all, as everywhere else
        DatabaseDeletionPolicy policy = policyFor(new CurrentUser());

        assertTrue(policy.canDelete(db("someone-else")));
        assertTrue(policy.canDelete((ManagedDatabase) null));
    }

    @Test
    void byName_looksUpTheMetadata() {
        ManagedDatabaseRepository repository = mock(ManagedDatabaseRepository.class);
        when(repository.find("pg", "mine")).thenReturn(Optional.of(db("alice")));
        when(repository.find("pg", "theirs")).thenReturn(Optional.of(db("bob")));
        when(repository.find("pg", "unknown")).thenReturn(Optional.empty());
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), repository);

        assertTrue(policy.canDelete("pg", "mine"));
        assertFalse(policy.canDelete("pg", "theirs"));
        assertFalse(policy.canDelete("pg", "unknown"));
    }

    @Test
    void canOverwrite_appliesTheDeletionRule() {
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), null);

        assertTrue(policy.canOverwrite(db("alice")));
        assertFalse(policy.canOverwrite(db("bob")));
    }

    @Test
    void canOverwrite_bypassesOutsideARequestScope() {
        // scheduled runs and expiration jobs carry no request identity; they run
        // work someone authorized when creating it, like TenantVisibility.bypass()
        CurrentUser detached = mock(CurrentUser.class);
        when(detached.hasPermission(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new jakarta.enterprise.context.ContextNotActiveException());
        DatabaseDeletionPolicy policy = TestDeletionPolicy.forUser(detached, null);

        assertTrue(policy.canOverwrite(db("someone-else")));
    }

    @Test
    void canArmDeletion_followsTheDeletionRule() {
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), null);

        assertTrue(policy.canArmDeletion(db("alice"), false));
        assertFalse(policy.canArmDeletion(db("bob"), false));
        assertFalse(policy.canArmDeletion(db("bob"), true), "creating-it never covers an EXISTING database");
    }

    @Test
    void canArmDeletion_ownGrantCoversADatabaseThisRequestCreates() {
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), null);

        // no metadata yet + the request creates it -> the caller will be its creator
        assertTrue(policy.canArmDeletion(null, true));
        assertFalse(policy.canArmDeletion(null, false), "an existing DB without metadata is nobody's own");
    }

    @Test
    void canArmDeletion_withoutAnyDeleteGrant_refusesEvenNewDatabases() {
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_OPERATE), null);

        assertFalse(policy.canArmDeletion(null, true));
        assertFalse(policy.canArmDeletion(db("alice"), false));
    }

    @Test
    void canArmDeletion_fullDelete_armsAnything() {
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE), null);

        assertTrue(policy.canArmDeletion(db("someone-else"), false));
        assertTrue(policy.canArmDeletion(null, false));
    }

    @Test
    void canArmDeletion_bypassesOutsideARequestScope() {
        CurrentUser detached = mock(CurrentUser.class);
        when(detached.hasPermission(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new jakarta.enterprise.context.ContextNotActiveException());
        DatabaseDeletionPolicy policy = TestDeletionPolicy.forUser(detached, null);

        assertTrue(policy.canArmDeletion(db("someone-else"), false));
    }

    @Test
    void canArmDeletion_byName_looksUpTheMetadata() {
        ManagedDatabaseRepository repository = mock(ManagedDatabaseRepository.class);
        when(repository.find("pg", "mine")).thenReturn(Optional.of(db("alice")));
        when(repository.find("pg", "new")).thenReturn(Optional.empty());
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), repository);

        assertTrue(policy.canArmDeletion("pg", "mine", false));
        assertTrue(policy.canArmDeletion("pg", "new", true));
        assertFalse(policy.canArmDeletion("pg", "new", false));
    }

    @Test
    void overwriteVerdict_protectionBeatsEveryGrant() {
        ManagedDatabaseRepository repository = mock(ManagedDatabaseRepository.class);
        ManagedDatabase shielded = db("alice");
        shielded.setProtectedFlag(true);
        when(repository.find("pg", "mydb")).thenReturn(Optional.of(shielded));
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE), repository);

        assertEquals(DatabaseDeletionPolicy.OverwriteVerdict.PROTECTED,
                policy.overwriteVerdict("pg", "mydb"));
    }

    @Test
    void overwriteVerdict_followsOwnership() {
        ManagedDatabaseRepository repository = mock(ManagedDatabaseRepository.class);
        when(repository.find("pg", "mine")).thenReturn(Optional.of(db("alice")));
        when(repository.find("pg", "theirs")).thenReturn(Optional.of(db("bob")));
        DatabaseDeletionPolicy policy =
                TestDeletionPolicy.forUser(userWith(Permission.DATABASE_DELETE_OWN), repository);

        assertEquals(DatabaseDeletionPolicy.OverwriteVerdict.ALLOWED, policy.overwriteVerdict("pg", "mine"));
        assertEquals(DatabaseDeletionPolicy.OverwriteVerdict.NOT_OWNER, policy.overwriteVerdict("pg", "theirs"));
    }

    @Test
    void isCaller_ignoresSystemAndNullActors() {
        DatabaseDeletionPolicy policy = policyFor(userWith(Permission.DATABASE_DELETE_OWN));

        assertFalse(policy.isCaller(null));
        assertFalse(policy.isCaller("system"));
        assertTrue(policy.isCaller("alice"));
    }
}
