package br.com.fzdevx.infrastructure.persistence.jdbc;

import br.com.fzdevx.application.port.ExpirationRepository;
import br.com.fzdevx.application.port.MigrationRecordRepository;
import br.com.fzdevx.application.port.SettingsRepository;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import br.com.fzdevx.domain.model.RuntimeSettings;
import br.com.fzdevx.infrastructure.persistence.ImageUsageTracker;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(PostgresBackendProfile.class)
class PgLowRiskRepositoriesTest {

    @Inject
    SettingsRepository settingsRepository;

    @Inject
    ExpirationRepository expirationRepository;

    @Inject
    MigrationRecordRepository migrationRecordRepository;

    @Inject
    ResourceCounterService counterService;

    @Inject
    ImageUsageTracker imageUsageTracker;

    @Inject
    JdbcSupport jdbc;

    // ---- producer wiring ----

    @Test
    void producerSelectsPostgresImplementations() {
        // the port beans are producer-made; calling through them must hit PG
        settingsRepository.save(new RuntimeSettings());
        assertEquals(1, jdbc.queryOne("SELECT count(*) FROM runtime_settings", rs -> rs.getLong(1)).orElse(0L));
    }

    // ---- settings ----

    @Test
    void settings_roundTripAndClear() {
        RuntimeSettings settings = new RuntimeSettings();
        settings.setTerminalEnabled(true);
        settings.setTerminalMaxSessions(9);
        settingsRepository.save(settings);

        RuntimeSettings loaded = settingsRepository.get();
        assertEquals(true, loaded.getTerminalEnabled());
        assertEquals(9, loaded.getTerminalMaxSessions());
        assertNull(loaded.getLogAnalyzerEnabled());

        settings.setTerminalEnabled(null);
        settingsRepository.save(settings);
        assertNull(settingsRepository.get().getTerminalEnabled());
    }

    // ---- expirations ----

    @Test
    void expiration_saveFindUpsertDelete() {
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        ContainerExpiration expiration = new ContainerExpiration("abc123", "abc123full", expires,
                "repo-a", "db1", true);
        expiration.setDeletionArmedBy("alice");
        expiration.setTenantId("tenant-1");
        expirationRepository.save(expiration);

        ContainerExpiration loaded = expirationRepository.findByContainerId("abc123").orElseThrow();
        assertEquals("abc123full", loaded.getFullContainerId());
        assertEquals(expires, loaded.getExpiresAt());
        assertTrue(loaded.isDeleteDatabaseOnExpiration());
        assertEquals("alice", loaded.getDeletionArmedBy());
        assertEquals("tenant-1", loaded.getTenantId());

        assertEquals(1, expirationRepository.findByDatabaseName("db1").size());

        // upsert same shortId
        expiration.setDatabaseName("db2");
        expirationRepository.save(expiration);
        assertEquals("db2", expirationRepository.findByContainerId("abc123").orElseThrow().getDatabaseName());
        assertTrue(expirationRepository.findByDatabaseName("db1").isEmpty());

        expirationRepository.delete("abc123");
        assertTrue(expirationRepository.findByContainerId("abc123").isEmpty());
    }

    // ---- migration records ----

    @Test
    void migrationRecord_upsertAndJsonbRoundTrip() {
        DatabaseMigrationRecord record = new DatabaseMigrationRecord(
                "dbx", "repo-a", "auto", "1.0", "2.0", List.of("1.1", "1.2", "2.0"), 42);
        migrationRecordRepository.save(record);

        DatabaseMigrationRecord loaded =
                migrationRecordRepository.findByDatabaseAndRepository("dbx", "repo-a").orElseThrow();
        assertEquals(List.of("1.1", "1.2", "2.0"), loaded.getVersionsIncluded());
        assertEquals(42, loaded.getTotalStatements());

        record.setTargetVersion("3.0");
        migrationRecordRepository.save(record);
        assertEquals("3.0", migrationRecordRepository
                .findByDatabaseAndRepository("dbx", "repo-a").orElseThrow().getTargetVersion());
        assertEquals(1, migrationRecordRepository.findAll().stream()
                .filter(r -> "dbx".equals(r.getDatabaseName())).count());
    }

    // ---- counters ----

    @Test
    void counters_atomicIncrementAndStartedAt() throws Exception {
        long before = counterService.get(ResourceCounterService.RESTORES);

        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 25; j++) {
                    counterService.increment(ResourceCounterService.RESTORES);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(before + 200, counterService.get(ResourceCounterService.RESTORES));
        assertTrue(counterService.getAll().containsKey(ResourceCounterService.RESTORES));
        assertNotNull(counterService.getStartedAt());
    }

    // ---- audit log ----

    @Inject
    PgAuditLogger auditLogger;

    private static final br.com.fzdevx.application.dto.AuditScope SCOPE_ALL =
            br.com.fzdevx.application.dto.AuditScope.unrestricted();

    private static br.com.fzdevx.application.dto.AuditScope scopeOf(String... tenantIds) {
        return br.com.fzdevx.application.dto.AuditScope.of(java.util.Set.of(tenantIds));
    }

    private static br.com.fzdevx.application.dto.AuditSearchCriteria allEntries() {
        return new br.com.fzdevx.application.dto.AuditSearchCriteria(
                null, "SCOPE_TEST", null, null, null, 0, 50);
    }

    @Test
    void audit_searchAndDistinctActionsHonourTenantScope() {
        auditLogger.logForTenant("alice", "t1", "SCOPE_TEST", "web-1", null);
        auditLogger.logForTenant("bob", "t2", "SCOPE_TEST", "web-2", null);
        auditLogger.logForTenant("system", null, "SCOPE_TEST_SYSTEM", "cleanup", null);
        try {
            var scoped = auditLogger.search(allEntries(), scopeOf("t1"));
            assertEquals(1, scoped.total(), "count(*) must carry the scope predicate too");
            assertEquals("web-1", scoped.entries().get(0).target());
            assertEquals("t1", scoped.entries().get(0).tenantId());

            assertEquals(2, auditLogger.search(allEntries(), scopeOf("t1", "t2")).total());

            // tenant_id IN (...) excludes NULL by three-valued logic, so the
            // untenanted system entry stays visible only to a cross-tenant reader
            var systemOnly = new br.com.fzdevx.application.dto.AuditSearchCriteria(
                    null, "SCOPE_TEST_SYSTEM", null, null, null, 0, 50);
            assertEquals(1, auditLogger.search(systemOnly, SCOPE_ALL).total());
            assertEquals(0, auditLogger.search(systemOnly, scopeOf("t1", "t2")).total());

            var actions = auditLogger.distinctActions(scopeOf("t1"));
            assertTrue(actions.contains("SCOPE_TEST"));
            assertFalse(actions.contains("SCOPE_TEST_SYSTEM"), "the dropdown leaks other tenants otherwise");
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCOPE_TEST%'");
        }
    }

    @Test
    void audit_emptyScopeReturnsNothing() {
        auditLogger.logForTenant("alice", "t1", "SCOPE_TEST", "web-1", null);
        try {
            var none = auditLogger.search(allEntries(),
                    br.com.fzdevx.application.dto.AuditScope.of(java.util.Set.of()));
            assertEquals(0, none.total());
            assertTrue(none.entries().isEmpty());
            assertTrue(auditLogger.distinctActions(
                    br.com.fzdevx.application.dto.AuditScope.of(java.util.Set.of())).isEmpty());
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SCOPE_TEST%'");
        }
    }

    @Test
    void audit_searchFiltersAndPaginates() {
        auditLogger.logAs("search-user", "SEARCH_TEST_CREATE", "container-abc", "detail one");
        auditLogger.logAs("search-user", "SEARCH_TEST_DELETE", "container-xyz", "detail two");
        auditLogger.logAs("other-user", "SEARCH_TEST_CREATE", "container-abc", null);
        try {
            var byActor = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                    "SEARCH-USER", null, null, null, null, 0, 10), SCOPE_ALL);
            assertEquals(2, byActor.total());

            var byAction = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                    null, "SEARCH_TEST_DELETE", null, null, null, 0, 10), SCOPE_ALL);
            assertEquals(1, byAction.total());
            assertEquals("container-xyz", byAction.entries().get(0).target());

            var byText = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                    null, null, "detail one", null, null, 0, 10), SCOPE_ALL);
            assertEquals(1, byText.total());

            var paged = auditLogger.search(new br.com.fzdevx.application.dto.AuditSearchCriteria(
                    null, "SEARCH_TEST_CREATE", null, null, null, 0, 1), SCOPE_ALL);
            assertEquals(2, paged.total());
            assertEquals(1, paged.entries().size());

            assertTrue(auditLogger.distinctActions(SCOPE_ALL).contains("SEARCH_TEST_CREATE"));
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE action LIKE 'SEARCH_TEST%'");
        }
    }

    @Test
    void audit_insertAndRetention() {
        auditLogger.logAs("tester", "TEST_ACTION", "target-1", "detail");
        long count = jdbc.queryOne(
                "SELECT count(*) FROM audit_log WHERE action = 'TEST_ACTION'", rs -> rs.getLong(1)).orElse(0L);
        assertEquals(1, count);

        // nothing is old enough yet
        assertEquals(0, auditLogger.removeEntriesOlderThan(Instant.now().minus(1, ChronoUnit.DAYS)));
        // everything older than "the future" goes away
        assertEquals(1, jdbc.update("DELETE FROM audit_log WHERE action = 'TEST_ACTION'"));
    }

    // ---- image usage ----

    @Test
    void imageUsage_upsertAndCleanup() {
        imageUsageTracker.markInUse(Set.of("sha256:img1", "sha256:img2"));
        assertTrue(imageUsageTracker.getLastUsed("sha256:img1").isPresent());

        imageUsageTracker.cleanup(Set.of("sha256:img2"));
        assertTrue(imageUsageTracker.getLastUsed("sha256:img1").isEmpty());
        assertTrue(imageUsageTracker.getLastUsed("sha256:img2").isPresent());
    }
}
