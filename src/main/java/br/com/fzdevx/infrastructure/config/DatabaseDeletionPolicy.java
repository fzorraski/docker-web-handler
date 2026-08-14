package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.auth.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Who may drop which database. DATABASE_DELETE means any visible database;
 * DATABASE_DELETE_OWN means only databases whose {@code createdBy} matches the
 * caller - the "clean up after your own restore" grant for roles that must not
 * touch anyone else's data.
 *
 * <p>A database without metadata, or whose creator was never recorded (created
 * before creator stamping, or by a legacy-mode install), is nobody's "own":
 * delete-own callers are refused rather than guessed at. With RBAC off,
 * {@link CurrentUser#hasPermission} grants everything, as everywhere else.</p>
 */
@ApplicationScoped
public class DatabaseDeletionPolicy {

    @Inject
    CurrentUser currentUser;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    /** Whether the caller may delete databases regardless of who created them. */
    public boolean canDeleteAny() {
        return currentUser.hasPermission(Permission.DATABASE_DELETE);
    }

    /**
     * Whether the caller may restore into an EXISTING database. A {@code --clean}
     * restore destroys the current contents, so the rule is the deletion rule -
     * overwriting through the restore dialog or container creation must not be a
     * side door around DATABASE_DELETE. Unlike the delete endpoints this is also
     * reached from worker threads (scheduled runs, expiration jobs), which carry
     * no request identity: they run work someone authorized when creating it, so
     * they bypass, exactly like {@link TenantVisibility#bypass()}.
     */
    public boolean canOverwrite(ManagedDatabase metadata) {
        try {
            return canDelete(metadata);
        } catch (jakarta.enterprise.context.ContextNotActiveException e) {
            return true;
        }
    }

    /**
     * Whether the caller may ARM automatic deletion of a database (delete on
     * container expiration). The rule is the deletion rule, with one addition:
     * a database that does not exist yet but that the same request will create
     * counts as the caller's own - the restore stamps them as its creator the
     * moment it is made. Worker threads (scheduled runs) bypass like
     * {@link #canOverwrite}: stored work was authorized when it was written.
     */
    public boolean canArmDeletion(ManagedDatabase metadata, boolean creatingIt) {
        try {
            return canDelete(metadata)
                    || (metadata == null && creatingIt
                            && currentUser.hasPermission(Permission.DATABASE_DELETE_OWN));
        } catch (jakarta.enterprise.context.ContextNotActiveException e) {
            return true;
        }
    }

    /** Lookup-included variant, so every arming door shares one guard call. */
    public boolean canArmDeletion(String repository, String databaseName, boolean creatingIt) {
        return canArmDeletion(
                managedDatabaseRepository.find(repository, databaseName).orElse(null), creatingIt);
    }

    /** Why an overwrite of an existing database is (dis)allowed. */
    public enum OverwriteVerdict { ALLOWED, PROTECTED, NOT_OWNER }

    /**
     * Both overwrite fences in one lookup: protection first (absolute, mirrors
     * the delete endpoint's 409), then the ownership rule of {@link #canOverwrite}.
     * Callers keep their flow-specific error wording; the decision lives here so
     * a rule change cannot miss one of the destructive doors.
     */
    public OverwriteVerdict overwriteVerdict(String repository, String databaseName) {
        var metadata = managedDatabaseRepository.find(repository, databaseName);
        if (metadata.map(ManagedDatabase::isProtectedFlag).orElse(false)) {
            return OverwriteVerdict.PROTECTED;
        }
        return canOverwrite(metadata.orElse(null))
                ? OverwriteVerdict.ALLOWED : OverwriteVerdict.NOT_OWNER;
    }

    public boolean canDelete(ManagedDatabase metadata) {
        return canDeleteAny()
                || (currentUser.hasPermission(Permission.DATABASE_DELETE_OWN) && ownedByCaller(metadata));
    }

    public boolean canDelete(String repository, String databaseName) {
        return canDeleteAny()
                || canDelete(managedDatabaseRepository.find(repository, databaseName).orElse(null));
    }

    /** Whether this database's recorded creator is the current caller. */
    public boolean ownedByCaller(ManagedDatabase metadata) {
        return metadata != null && isCaller(metadata.getCreatedBy());
    }

    /** Whether a recorded creator name refers to the current caller. */
    public boolean isCaller(String createdBy) {
        String username = currentUser.getUsername();
        // findByUsername matches case-insensitively, so ownership does too
        return createdBy != null && username != null && createdBy.equalsIgnoreCase(username);
    }
}
