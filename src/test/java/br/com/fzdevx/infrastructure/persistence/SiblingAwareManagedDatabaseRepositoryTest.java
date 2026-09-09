package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.RepositorySiblingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiblingAwareManagedDatabaseRepositoryTest {

    @TempDir Path tempDir;
    JsonFileManagedDatabaseRepository raw;
    SiblingAwareManagedDatabaseRepository repo;

    @BeforeEach
    void setUp() {
        raw = new JsonFileManagedDatabaseRepository(tempDir.resolve("managed.json").toString());
        RepositorySiblingResolver resolver = mock(RepositorySiblingResolver.class);
        when(resolver.siblings(any(), any())).thenAnswer(inv -> {
            String repo = inv.getArgument(0, String.class);
            if (repo == null) return List.of();
            if (repo.equals("a")) return List.of("a", "b");
            if (repo.equals("b")) return List.of("b", "a");
            return List.of(repo);
        });
        when(resolver.serverKey(anyString())).thenReturn(Optional.empty());
        when(resolver.serverKey("a")).thenReturn(Optional.of("host:5432"));
        when(resolver.serverKey("b")).thenReturn(Optional.of("host:5432"));
        repo = new SiblingAwareManagedDatabaseRepository(raw, resolver);
    }

    private ManagedDatabase stored(String repository, String name) {
        ManagedDatabase md = new ManagedDatabase(repository, name);
        md.setCreatedBy("alice");
        raw.save(md);
        return md;
    }

    @Test
    void protectionSetThroughOneSibling_isVisibleThroughTheOther() {
        stored("a", "acme");
        assertTrue(repo.update("a", "acme", md -> md.setProtectedFlag(true)));

        assertTrue(repo.find("b", "acme").orElseThrow().isProtectedFlag());
        assertEquals("alice", repo.find("b", "ACME").orElseThrow().getCreatedBy());
    }

    @Test
    void updateThroughSibling_writesTheStoredRow_withoutCreatingASecondOne() {
        stored("a", "acme");

        assertTrue(repo.update("b", "acme", md -> md.setDescription("via b")));

        List<ManagedDatabase> all = raw.findAll();
        assertEquals(1, all.size());
        assertEquals("a", all.getFirst().getRepository());
        assertEquals("via b", all.getFirst().getDescription());
    }

    @Test
    void saveThroughSibling_mergesIntoExistingRow() {
        ManagedDatabase existing = new ManagedDatabase("a", "acme");
        existing.setProtectedFlag(true);
        raw.save(existing);

        ManagedDatabase incoming = new ManagedDatabase("b", "acme");
        incoming.setCreatedBy("bob");
        incoming.setTenantId("t1");
        repo.save(incoming);

        List<ManagedDatabase> all = raw.findAll();
        assertEquals(1, all.size());
        ManagedDatabase row = all.getFirst();
        assertEquals("a", row.getRepository());
        assertTrue(row.isProtectedFlag());
        assertEquals("bob", row.getCreatedBy());
        assertEquals("t1", row.getTenantId());
    }

    @Test
    void saveWithoutSiblingRow_createsUnderActingRepository() {
        repo.save(new ManagedDatabase("b", "fresh"));

        assertEquals("b", raw.find("b", "fresh").orElseThrow().getRepository());
        assertTrue(repo.find("a", "fresh").isPresent());
    }

    @Test
    void deleteThroughSibling_removesTheRowEverywhere() {
        stored("a", "acme");
        raw.save(new ManagedDatabase("b", "acme"));

        repo.delete("b", "acme");

        assertTrue(raw.findAll().isEmpty());
        assertTrue(repo.find("a", "acme").isEmpty());
    }

    @Test
    void findByRepository_returnsOneMergedRecordPerDatabase() {
        ManagedDatabase inA = new ManagedDatabase("a", "acme");
        inA.setProtectedFlag(true);
        raw.save(inA);
        ManagedDatabase inB = new ManagedDatabase("b", "ACME");
        inB.setCreatedBy("alice");
        raw.save(inB);
        raw.save(new ManagedDatabase("a", "solo"));

        List<ManagedDatabase> listed = repo.findByRepository("b");

        assertEquals(2, listed.size());
        ManagedDatabase acme = listed.stream().filter(m -> m.getName().equalsIgnoreCase("acme")).findFirst().orElseThrow();
        assertTrue(acme.isProtectedFlag());
        assertEquals("alice", acme.getCreatedBy());
    }

    @Test
    void bulkMarkUsed_bumpsTheSiblingRow_andCreatesOnlyUnknownNames() {
        stored("a", "acme");
        Instant t = Instant.parse("2026-05-01T00:00:00Z");

        repo.bulkMarkUsed("b", Map.of("acme", t, "newdb", t));

        List<ManagedDatabase> all = raw.findAll();
        assertEquals(2, all.size());
        assertEquals(t, raw.find("a", "acme").orElseThrow().getAppLastUsedAt());
        assertTrue(raw.find("b", "acme").isEmpty(), "no duplicate under the acting repository");
        assertEquals("b", raw.find("b", "newdb").orElseThrow().getRepository());
    }

    @Test
    void nonSiblingRepository_staysIsolated() {
        stored("a", "acme");
        repo.update("a", "acme", md -> md.setProtectedFlag(true));

        assertTrue(repo.find("c", "acme").isEmpty());
        repo.save(new ManagedDatabase("c", "acme"));
        assertEquals(2, raw.findAll().size());
        assertFalse(repo.find("c", "acme").orElseThrow().isProtectedFlag());
    }

    @Test
    void updateWithLeftoverDuplicates_foldsThemFirst_soReadsAndWritesAgree() {
        ManagedDatabase inA = new ManagedDatabase("a", "mydb");
        inA.setCreatedBy("alice");
        raw.save(inA);
        ManagedDatabase inB = new ManagedDatabase("b", "mydb");
        inB.setProtectedFlag(true);
        raw.save(inB);
        assertTrue(repo.find("b", "mydb").orElseThrow().isProtectedFlag(), "merged read sees the protection");

        assertTrue(repo.update("b", "mydb", md -> md.setProtectedFlag(!md.isProtectedFlag())));

        assertEquals(1, raw.findAll().size(), "the loser row is gone");
        ManagedDatabase row = raw.findAll().getFirst();
        assertEquals("a", row.getRepository());
        assertEquals("alice", row.getCreatedBy());
        assertFalse(row.isProtectedFlag(), "the toggle applied to the folded row, so it can be cleared");
        assertFalse(repo.find("b", "mydb").orElseThrow().isProtectedFlag());
    }

    @Test
    void nullRepository_isEmptyNotAnError() {
        stored("a", "acme");

        assertTrue(repo.find(null, "acme").isEmpty());
        assertFalse(repo.update(null, "acme", md -> md.setProtectedFlag(true)));
        repo.delete(null, "acme");
        assertEquals(1, raw.findAll().size());
    }

    @Test
    void concurrentSavesFromBothSiblings_yieldOneRow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Thread t1 = new Thread(() -> { await(start); repo.save(new ManagedDatabase("a", "race")); });
        Thread t2 = new Thread(() -> { await(start); repo.save(new ManagedDatabase("b", "race")); });
        t1.start(); t2.start();
        start.countDown();
        t1.join(); t2.join();

        assertEquals(1, raw.findAll().size());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
