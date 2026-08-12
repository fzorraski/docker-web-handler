package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.dto.AuditScope;
import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.TestTenantVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileAuditLoggerTest {

    @TempDir Path tempDir;

    FileAuditLogger auditLogger;
    Path auditFile;

    @BeforeEach
    void setUp() {
        auditFile = tempDir.resolve("audit.log");
        auditLogger = new FileAuditLogger();
        auditLogger.enabled = true;
        auditLogger.file = auditFile.toString();
        auditLogger.currentUser = new CurrentUser();
        auditLogger.tenantVisibility = TestTenantVisibility.forUser(auditLogger.currentUser, null);
    }

    /** Puts the logger in RBAC mode as a member of the given tenants. */
    private void actAs(String username, String... tenantIds) {
        auditLogger.currentUser.set("u-" + username, username,
                Set.of(Permission.CONTAINERS_RUN), Set.of(tenantIds));
    }

    private static AuditScope scopeOf(String... tenantIds) {
        return AuditScope.of(Set.of(tenantIds));
    }

    @Test
    void search_filtersAndPaginatesNewestFirst() {
        auditLogger.logAs("alice", "LOGIN", "session", "ip=1.1.1.1");
        auditLogger.logAs("alice", "CONTAINER_CREATE", "web-1", "image=nginx");
        auditLogger.logAs("bob", "CONTAINER_REMOVE", "web-1", null);

        var byActor = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                "ALICE", null, null, null, null, 0, 10), AuditScope.unrestricted());
        assertEquals(2, byActor.total());
        // newest first
        assertEquals("CONTAINER_CREATE", byActor.entries().get(0).action());

        var byText = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, "web-1", null, null, 0, 1), AuditScope.unrestricted());
        assertEquals(2, byText.total());
        assertEquals(1, byText.entries().size());

        assertEquals(List.of("CONTAINER_CREATE", "CONTAINER_REMOVE", "LOGIN"), auditLogger.distinctActions(AuditScope.unrestricted()));
    }

    @Test
    void log_rbacUser_writesJsonLineWithUsername() throws Exception {
        auditLogger.currentUser.set("u1", "alice", Set.of(Permission.CONTAINERS_RUN));

        auditLogger.log("CONTAINER_CREATE", "my-app", "image=postgres:16");

        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.contains("\"user\":\"alice\""));
        assertTrue(line.contains("\"action\":\"CONTAINER_CREATE\""));
        assertTrue(line.contains("\"target\":\"my-app\""));
        assertTrue(line.contains("\"detail\":\"image=postgres:16\""));
        assertTrue(line.contains("\"timestamp\":\""));
    }

    @Test
    void log_withoutRbac_recordsAnonymous() throws Exception {
        auditLogger.log("CONTAINER_STOP", "abc123", null);

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("\"user\":\"anonymous\""));
        assertFalse(line.contains("\"detail\""));
    }

    @Test
    void logAs_usesExplicitActor() throws Exception {
        auditLogger.logAs("bob", "LOGIN", "session", "ip=10.0.0.1");

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("\"user\":\"bob\""));
        assertTrue(line.contains("\"action\":\"LOGIN\""));
    }

    @Test
    void log_escapesJsonSpecialCharacters() throws Exception {
        auditLogger.logAs("eve", "ROLE_CREATE", "quote\"back\\slash", "line\nbreak");

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("quote\\\"back\\\\slash"));
        assertTrue(line.contains("line\\nbreak"));
    }

    @Test
    void log_appendsAcrossCalls() throws Exception {
        auditLogger.logAs("a", "LOGIN", "session", null);
        auditLogger.logAs("b", "LOGIN", "session", null);

        assertEquals(2, Files.readAllLines(auditFile).size());
    }

    @Test
    void log_disabled_writesNothing() {
        auditLogger.enabled = false;

        auditLogger.logAs("alice", "LOGIN", "session", null);

        assertFalse(Files.exists(auditFile));
    }

    @Test
    void removeEntriesOlderThan_dropsOldKeepsRecentAndUnparsable() throws Exception {
        Files.writeString(auditFile, String.join("\n",
                "{\"timestamp\":\"2020-01-01T00:00:00Z\",\"user\":\"old\",\"action\":\"LOGIN\",\"target\":\"session\"}",
                "not-a-json-line",
                "{\"timestamp\":\"2099-01-01T00:00:00Z\",\"user\":\"future\",\"action\":\"LOGIN\",\"target\":\"session\"}") + "\n");

        int removed = auditLogger.removeEntriesOlderThan(java.time.Instant.parse("2021-01-01T00:00:00Z"));

        assertEquals(1, removed);
        List<String> lines = Files.readAllLines(auditFile);
        assertEquals(2, lines.size());
        assertEquals("not-a-json-line", lines.get(0));
        assertTrue(lines.get(1).contains("\"user\":\"future\""));
    }

    @Test
    void removeEntriesOlderThan_missingFileOrNothingToRemove_isNoOp() throws Exception {
        assertEquals(0, auditLogger.removeEntriesOlderThan(java.time.Instant.now()));

        auditLogger.logAs("alice", "LOGIN", "session", null);
        assertEquals(0, auditLogger.removeEntriesOlderThan(java.time.Instant.parse("2000-01-01T00:00:00Z")));
        assertEquals(1, Files.readAllLines(auditFile).size());
    }

    @Test
    void log_createsParentDirectories() throws Exception {
        auditLogger.file = tempDir.resolve("nested/dir/audit.log").toString();

        auditLogger.logAs("alice", "LOGIN", "session", null);

        assertEquals(1, Files.readAllLines(tempDir.resolve("nested/dir/audit.log")).size());
    }

    // ---- tenant scoping ----

    @Test
    void log_rbacUserWithTenant_stampsTenantLast() throws Exception {
        actAs("alice", "t1");

        auditLogger.log("CONTAINER_CREATE", "web-1", "image=nginx");

        String line = Files.readAllLines(auditFile).get(0);
        assertTrue(line.contains("\"tenant\":\"t1\""));
        // retention parses the timestamp off the raw line by this prefix
        assertTrue(line.startsWith("{\"timestamp\":\""));
    }

    @Test
    void log_tenantlessUser_stampsNoTenant() throws Exception {
        actAs("root");

        auditLogger.log("SETTINGS_UPDATE", "settings", null);

        assertFalse(Files.readAllLines(auditFile).get(0).contains("\"tenant\""));
    }

    @Test
    void search_tenantScope_hidesOtherTenantsAndUntenantedEntries() {
        actAs("alice", "t1");
        auditLogger.log("CONTAINER_CREATE", "web-1", null);
        actAs("bob", "t2");
        auditLogger.log("CONTAINER_REMOVE", "web-2", null);
        auditLogger.logForTenant("system", null, "CLEANUP", "images", null);

        var scoped = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, null, null, null, 0, 50), scopeOf("t1"));

        // total must be filtered too, or paging reports rows the caller cannot see
        assertEquals(1, scoped.total());
        assertEquals("CONTAINER_CREATE", scoped.entries().get(0).action());
        assertEquals("t1", scoped.entries().get(0).tenantId());
    }

    @Test
    void search_unrestrictedScope_seesEverythingIncludingUntenanted() {
        actAs("alice", "t1");
        auditLogger.log("CONTAINER_CREATE", "web-1", null);
        auditLogger.logForTenant("system", null, "CLEANUP", "images", null);

        var all = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, null, null, null, 0, 50), AuditScope.unrestricted());

        assertEquals(2, all.total());
    }

    @Test
    void search_emptyScope_returnsNothing() {
        actAs("alice", "t1");
        auditLogger.log("CONTAINER_CREATE", "web-1", null);

        var none = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, null, null, null, 0, 50), AuditScope.of(Set.of()));

        assertEquals(0, none.total());
        assertTrue(none.entries().isEmpty());
    }

    @Test
    void distinctActions_tenantScope_omitsOtherTenantsActions() {
        actAs("alice", "t1");
        auditLogger.log("CONTAINER_CREATE", "web-1", null);
        actAs("bob", "t2");
        auditLogger.log("TENANT_DELETE", "other-squad", null);

        // the dropdown leaks action names just as readily as the table leaks rows
        assertEquals(List.of("CONTAINER_CREATE"), auditLogger.distinctActions(scopeOf("t1")));
    }

    @Test
    void readsLegacyLinesWithoutTenantField() throws Exception {
        Files.writeString(auditFile,
                "{\"timestamp\":\"2024-01-01T00:00:00Z\",\"user\":\"old\",\"action\":\"LOGIN\",\"target\":\"session\"}\n");

        var unrestricted = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, null, null, null, 0, 50), AuditScope.unrestricted());
        assertEquals(1, unrestricted.total());
        assertNull(unrestricted.entries().get(0).tenantId());

        // pre-migration entries carry no tenant, so they stay cross-tenant only
        var scoped = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, null, null, null, null, 0, 50), scopeOf("t1"));
        assertEquals(0, scoped.total());
    }
}
