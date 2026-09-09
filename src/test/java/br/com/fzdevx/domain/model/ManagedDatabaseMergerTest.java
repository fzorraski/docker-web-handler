package br.com.fzdevx.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagedDatabaseMergerTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-03-01T00:00:00Z");

    private static ManagedDatabase row(String repo, String name, Instant createdAt, String creator) {
        ManagedDatabase md = new ManagedDatabase(repo, name);
        md.setCreatedAt(createdAt);
        md.setCreatedBy(creator);
        return md;
    }

    @Test
    void singleCreator_winsDescriptiveFields() {
        ManagedDatabase blank = row("qa", "acme", T1, null);
        blank.setDescription("from qa");
        ManagedDatabase creator = row("prod", "acme", T2, "alice");
        creator.setTenantId("t1");

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(blank, creator));

        assertEquals("prod", merged.getRepository());
        assertEquals("alice", merged.getCreatedBy());
        assertEquals("t1", merged.getTenantId());
        assertEquals("from qa", merged.getDescription(), "winner had no description, so the loser's is kept");
        assertEquals(T1, merged.getCreatedAt(), "earliest creation time");
    }

    @Test
    void noCreator_oldestWins() {
        ManagedDatabase newer = row("qa", "acme", T2, null);
        newer.setDescription("newer");
        ManagedDatabase older = row("prod", "acme", T1, null);
        older.setDescription("older");

        assertSame(older, ManagedDatabaseMerger.winner(List.of(newer, older)));
        assertEquals("older", ManagedDatabaseMerger.merge(List.of(newer, older)).getDescription());
    }

    @Test
    void twoCreators_oldestWins_andKeepsItsCreator() {
        ManagedDatabase a = row("qa", "acme", T3, "bob");
        ManagedDatabase b = row("prod", "acme", T1, "alice");

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(a, b));

        assertEquals("alice", merged.getCreatedBy());
        assertEquals("prod", merged.getRepository());
    }

    @Test
    void creatorBeatsOlderCreatorlessRow_andOldestCreatorWinsAmongCreators() {
        ManagedDatabase a = row("qa", "acme", Instant.parse("2026-02-01T00:00:00Z"), "alice");
        ManagedDatabase b = row("dev", "acme", Instant.parse("2026-01-01T00:00:00Z"), "bob");
        ManagedDatabase c = row("prod", "acme", Instant.parse("2025-12-01T00:00:00Z"), null);
        c.setDescription("only c knows");

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(c, a, b));

        assertSame(b, ManagedDatabaseMerger.winner(List.of(c, a, b)));
        assertEquals("bob", merged.getCreatedBy());
        assertEquals("dev", merged.getRepository());
        assertEquals("only c knows", merged.getDescription());
        assertEquals(Instant.parse("2025-12-01T00:00:00Z"), merged.getCreatedAt());
    }

    @Test
    void mergeInto_sameKeyRows_keepsStoredFieldsWhenIncomingWins() {
        ManagedDatabase stored = row("repoA", "mydb", T1, null);
        stored.setProtectedFlag(true);
        stored.setDescription("prod db");
        stored.setTenantId("t1");
        stored.setAppLastUsedAt(T2);
        ManagedDatabase incoming = row("repoA", "mydb", T3, "bob");

        ManagedDatabaseMerger.mergeInto(stored, incoming);

        assertTrue(stored.isProtectedFlag());
        assertEquals("prod db", stored.getDescription());
        assertEquals("t1", stored.getTenantId());
        assertEquals(T2, stored.getAppLastUsedAt());
        assertEquals("bob", stored.getCreatedBy());
        assertEquals(T1, stored.getCreatedAt());
    }

    @Test
    void nullCreatedAt_sortsLast() {
        ManagedDatabase unknown = row("qa", "acme", null, null);
        ManagedDatabase dated = row("prod", "acme", T2, null);
        assertSame(dated, ManagedDatabaseMerger.winner(List.of(unknown, dated)));
        assertSame(unknown, ManagedDatabaseMerger.winner(List.of(unknown)));
    }

    @Test
    void protectionIsOrEd_usageIsLatest_creationIsEarliest() {
        ManagedDatabase winner = row("prod", "acme", T2, "alice");
        winner.setAppLastUsedAt(T1);
        ManagedDatabase loser = row("qa", "acme", T1, null);
        loser.setProtectedFlag(true);
        loser.setAppLastUsedAt(T3);

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(winner, loser));

        assertTrue(merged.isProtectedFlag());
        assertEquals(T3, merged.getAppLastUsedAt());
        assertEquals(T1, merged.getCreatedAt());
    }

    @Test
    void nullFill_neverOverridesTheWinner() {
        ManagedDatabase winner = row("prod", "acme", T1, "alice");
        winner.setDescription("keep me");
        ManagedDatabase loser = row("qa", "acme", T2, null);
        loser.setDescription("ignored");
        loser.setLastRestoredFrom("dump.sql");
        loser.setLastRestoredAt(T3);
        loser.setLastRestoredBy("carol");

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(winner, loser));

        assertEquals("keep me", merged.getDescription());
        assertEquals("dump.sql", merged.getLastRestoredFrom());
        assertEquals(T3, merged.getLastRestoredAt());
        assertEquals("carol", merged.getLastRestoredBy());
    }

    @Test
    void merge_keepsWinnerCasing_andDoesNotMutateInputs() {
        ManagedDatabase winner = row("Prod", "AcMe", T1, "alice");
        ManagedDatabase loser = row("qa", "acme", T2, null);
        loser.setProtectedFlag(true);

        ManagedDatabase merged = ManagedDatabaseMerger.merge(List.of(winner, loser));

        assertEquals("Prod", merged.getRepository());
        assertEquals("AcMe", merged.getName());
        assertFalse(winner.isProtectedFlag(), "input must not be mutated");
        assertNotSame(winner, merged);
    }

    @Test
    void mergeInto_keepsStoredIdentity_butTakesIncomingCreator() {
        ManagedDatabase stored = row("qa", "acme", T2, null);
        ManagedDatabase incoming = row("prod", "acme", T3, "alice");
        incoming.setTenantId("t1");

        ManagedDatabaseMerger.mergeInto(stored, incoming);

        assertEquals("qa", stored.getRepository());
        assertEquals("alice", stored.getCreatedBy());
        assertEquals("t1", stored.getTenantId());
        assertEquals(T2, stored.getCreatedAt());
    }
}
