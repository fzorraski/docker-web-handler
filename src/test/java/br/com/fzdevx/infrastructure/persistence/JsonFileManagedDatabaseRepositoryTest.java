package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.domain.model.ManagedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileManagedDatabaseRepositoryTest {

    @TempDir Path tempDir;
    JsonFileManagedDatabaseRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JsonFileManagedDatabaseRepository(tempDir.resolve("managed.json").toString());
    }

    @Test
    void bulkMarkUsed_createsMissingRecordsAndUpdatesExisting() {
        ManagedDatabase existing = new ManagedDatabase("repo1", "db1");
        existing.setAppLastUsedAt(Instant.parse("2026-01-01T00:00:00Z"));
        repo.save(existing);

        Instant t1 = Instant.parse("2026-05-29T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-29T11:00:00Z");
        repo.bulkMarkUsed("repo1", Map.of("db1", t1, "db2", t2));

        Optional<ManagedDatabase> db1 = repo.find("repo1", "db1");
        Optional<ManagedDatabase> db2 = repo.find("repo1", "db2");
        assertTrue(db1.isPresent());
        assertTrue(db2.isPresent(), "missing records must be created");
        assertEquals(t1, db1.get().getAppLastUsedAt(), "existing record's timestamp must be updated");
        assertEquals(t2, db2.get().getAppLastUsedAt());
    }

    @Test
    void bulkMarkUsed_skipsTimestampRegressions() {
        ManagedDatabase existing = new ManagedDatabase("repo1", "db1");
        Instant newer = Instant.parse("2026-05-29T12:00:00Z");
        existing.setAppLastUsedAt(newer);
        repo.save(existing);

        Instant older = Instant.parse("2026-05-29T10:00:00Z");
        repo.bulkMarkUsed("repo1", Map.of("db1", older));

        ManagedDatabase reloaded = repo.find("repo1", "db1").orElseThrow();
        assertEquals(newer, reloaded.getAppLastUsedAt(),
                "an older proposed timestamp must not overwrite a newer existing one");
    }

    @Test
    void bulkMarkUsed_doesNotTouchOtherRepositories() {
        ManagedDatabase otherRepo = new ManagedDatabase("repo2", "db1");
        otherRepo.setAppLastUsedAt(Instant.parse("2026-01-01T00:00:00Z"));
        repo.save(otherRepo);

        repo.bulkMarkUsed("repo1", Map.of("db1", Instant.parse("2026-05-29T10:00:00Z")));

        ManagedDatabase reloaded = repo.find("repo2", "db1").orElseThrow();
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), reloaded.getAppLastUsedAt(),
                "repository scope must isolate updates");
    }

    @Test
    void bulkMarkUsed_emptyMap_isNoop() {
        repo.bulkMarkUsed("repo1", Map.of());
        // Nothing to assert apart from "did not throw".
        assertNotNull(repo.findAll());
    }

    @Test
    void bulkMarkUsed_caseInsensitive_updatesExistingMixedCaseRecord() {
        // Inline tracker saves a user-typed "MyDB"; poller observes PG's
        // canonical lowercase "mydb". The two MUST converge on a single record.
        ManagedDatabase mixedCase = new ManagedDatabase("repo1", "MyDB");
        mixedCase.setAppLastUsedAt(Instant.parse("2026-01-01T00:00:00Z"));
        repo.save(mixedCase);

        repo.bulkMarkUsed("repo1", Map.of("mydb", Instant.parse("2026-05-29T10:00:00Z")));

        assertEquals(1, repo.findByRepository("repo1").size(),
                "poller must update the existing MyDB record, not insert a duplicate 'mydb'");
        ManagedDatabase reloaded = repo.findByRepository("repo1").get(0);
        assertEquals("MyDB", reloaded.getName(), "existing case must be preserved");
        assertEquals(Instant.parse("2026-05-29T10:00:00Z"), reloaded.getAppLastUsedAt());
    }

    @Test
    void find_isCaseInsensitive() {
        ManagedDatabase mixedCase = new ManagedDatabase("MyRepo", "MyDB");
        repo.save(mixedCase);

        assertTrue(repo.find("myrepo", "mydb").isPresent(),
                "find must match case-insensitively so user-flow and PG-flow converge");
        assertTrue(repo.find("MYREPO", "MYDB").isPresent());
    }

    @Test
    void save_caseInsensitive_replacesExistingMixedCaseRecord() {
        ManagedDatabase first = new ManagedDatabase("repo1", "MyDB");
        first.setAppLastUsedAt(Instant.parse("2026-01-01T00:00:00Z"));
        repo.save(first);

        // A later save with a different case must replace, not duplicate.
        ManagedDatabase second = new ManagedDatabase("repo1", "mydb");
        second.setAppLastUsedAt(Instant.parse("2026-05-29T10:00:00Z"));
        repo.save(second);

        assertEquals(1, repo.findByRepository("repo1").size(),
                "save must dedupe by case-insensitive (repo,name)");
    }

    @Test
    void writeToFile_isAtomicLeavesNoTempArtifacts() throws Exception {
        Path target = tempDir.resolve("managed.json");
        repo.save(new ManagedDatabase("repo1", "db1"));
        assertTrue(java.nio.file.Files.exists(target));

        // After a successful write the target must exist and no stray .tmp.*
        // sibling must remain.
        try (var entries = java.nio.file.Files.list(tempDir)) {
            long tmpCount = entries.filter(p -> p.getFileName().toString().contains(".tmp.")).count();
            assertEquals(0, tmpCount, "atomic move must leave no leftover temp file");
        }
    }
}
