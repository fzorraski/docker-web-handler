package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.AuditScope;
import br.com.fzdevx.application.dto.AuditSearchCriteria;
import br.com.fzdevx.application.dto.AuditSearchResult;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.TestTenantVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller is the only caller of the audit reads, so it is the one place
 * that decides which tenants a reader may browse. These tests pin that wiring:
 * cross-tenant readers get an unrestricted scope, everyone else gets exactly
 * their own memberships - for the action dropdown as much as for the table.
 */
class AuditControllerTest {

    /** Records the scope it was queried with. */
    static final class RecordingAuditLogger implements AuditLogger {
        AuditScope searchScope;
        AuditScope actionsScope;

        @Override public void log(String action, String target, String detail) { }
        @Override public void logAs(String actor, String action, String target, String detail) { }
        @Override public void logForTenant(String actor, String tenantId, String action,
                                           String target, String detail) { }
        @Override public int removeEntriesOlderThan(Instant cutoff) { return 0; }

        @Override public AuditSearchResult search(AuditSearchCriteria criteria, AuditScope scope) {
            this.searchScope = scope;
            return new AuditSearchResult(List.of(), 0);
        }

        @Override public List<String> distinctActions(AuditScope scope) {
            this.actionsScope = scope;
            return List.of();
        }
    }

    private AuditController controller;
    private RecordingAuditLogger auditLogger;
    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        auditLogger = new RecordingAuditLogger();
        currentUser = new CurrentUser();
        controller = new AuditController();
        controller.auditLogger = auditLogger;
        controller.tenantVisibility = TestTenantVisibility.forUser(currentUser, null);
    }

    private void query() {
        controller.search(0, 25, null, null, null, null, null);
        controller.actions();
    }

    @Test
    void crossTenantReader_getsUnrestrictedScope() {
        currentUser.set("u1", "root", Set.of(Permission.AUDIT_LOG_VIEW, Permission.TENANTS_VIEW_ALL),
                Set.of("t1"));

        query();

        assertTrue(auditLogger.searchScope.isUnrestricted());
        assertTrue(auditLogger.actionsScope.isUnrestricted());
    }

    @Test
    void tenantScopedReader_isLimitedToOwnTenants() {
        currentUser.set("u2", "alice", Set.of(Permission.AUDIT_LOG_VIEW, Permission.USERS_MANAGE),
                Set.of("t1", "t2"));

        query();

        assertFalse(auditLogger.searchScope.isUnrestricted());
        assertEquals(Set.of("t1", "t2"), auditLogger.searchScope.tenantIds());
        // the dropdown must be scoped too, or it lists other tenants' actions
        assertEquals(Set.of("t1", "t2"), auditLogger.actionsScope.tenantIds());
    }

    @Test
    void superAdminWithoutTenantsViewAll_stillSeesEverything() {
        // a customized SUPER_ADMIN role may not carry TENANTS_VIEW_ALL; without
        // this they would get an empty trail and nothing explaining why
        currentUser.set("u4", "root", Set.of(Permission.AUDIT_LOG_VIEW, Permission.SYSTEM_CONFIG),
                Set.of());

        query();

        assertTrue(auditLogger.searchScope.isUnrestricted());
        assertTrue(auditLogger.actionsScope.isUnrestricted());
    }

    @Test
    void tenantlessScopedReader_getsEmptyScope() {
        currentUser.set("u3", "nobody", Set.of(Permission.AUDIT_LOG_VIEW), Set.of());

        query();

        assertFalse(auditLogger.searchScope.isUnrestricted());
        assertTrue(auditLogger.searchScope.tenantIds().isEmpty());
    }

    @Test
    void rbacDisabled_getsUnrestrictedScope() {
        // no CurrentUser.set(): legacy password mode, no tenant model at all
        query();

        assertTrue(auditLogger.searchScope.isUnrestricted());
        assertTrue(auditLogger.actionsScope.isUnrestricted());
    }
}
