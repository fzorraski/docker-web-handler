package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.RepositorySiblingResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedDatabaseSiblingMergeTaskTest {

    @TempDir Path tempDir;
    JsonFileManagedDatabaseRepository raw;
    RepositorySiblingResolver resolver;
    ManagedDatabaseSiblingMergeTask task;

    @BeforeEach
    void setUp() {
        raw = new JsonFileManagedDatabaseRepository(tempDir.resolve("managed.json").toString());
        resolver = mock(RepositorySiblingResolver.class);
        when(resolver.siblingGroups(any())).thenReturn(Map.of("host:5432", List.of("prod", "qa"), "other:5432", List.of("solo")));
        task = new ManagedDatabaseSiblingMergeTask();
        task.siblingResolver = resolver;
        task.fileRepository = raw;
        task.enabled = true;
    }

    private ManagedDatabase row(String repo, String name, String creator, boolean protectedFlag, Instant createdAt) {
        ManagedDatabase md = new ManagedDatabase(repo, name);
        md.setCreatedBy(creator);
        md.setProtectedFlag(protectedFlag);
        md.setCreatedAt(createdAt);
        raw.save(md);
        return md;
    }

    @Test
    void mergesDuplicatesAcrossSiblings_keepingProtectionAndCreator() {
        row("prod", "acme", "alice", false, Instant.parse("2026-01-01T00:00:00Z"));
        row("qa", "acme", null, true, Instant.parse("2026-02-01T00:00:00Z"));
        row("qa", "only_qa", null, false, Instant.parse("2026-02-01T00:00:00Z"));
        row("solo", "acme", null, false, Instant.parse("2026-02-01T00:00:00Z"));

        int removed = task.mergeDuplicates(raw);

        assertEquals(1, removed);
        List<ManagedDatabase> all = raw.findAll();
        assertEquals(3, all.size());
        ManagedDatabase acme = raw.find("prod", "acme").orElseThrow();
        assertTrue(acme.isProtectedFlag());
        assertEquals("alice", acme.getCreatedBy());
        assertTrue(raw.find("qa", "acme").isEmpty());
        assertTrue(raw.find("qa", "only_qa").isPresent());
        assertTrue(raw.find("solo", "acme").isPresent(), "non-sibling rows are untouched");
    }

    @Test
    void secondRun_changesNothing() {
        row("prod", "acme", "alice", false, Instant.parse("2026-01-01T00:00:00Z"));
        row("qa", "ACME", null, true, Instant.parse("2026-02-01T00:00:00Z"));

        assertEquals(1, task.mergeDuplicates(raw));
        assertEquals(0, task.mergeDuplicates(raw));
        assertEquals(1, raw.findAll().size());
    }

    @Test
    void noSiblingGroup_writesNothing() {
        when(resolver.siblingGroups(any())).thenReturn(Map.of("host:5432", List.of("prod")));
        row("prod", "acme", null, false, Instant.parse("2026-01-01T00:00:00Z"));
        ManagedDatabaseRepository spy = spy(raw);

        assertEquals(0, task.mergeDuplicates(spy));

        verify(spy, never()).save(any());
        verify(spy, never()).delete(any(), any());
    }

    @Test
    void repositoriesHoldingRows_areOfferedToTheResolver() {
        row("prod", "acme", "alice", false, Instant.parse("2026-01-01T00:00:00Z"));
        row("legacy", "acme", null, true, Instant.parse("2026-02-01T00:00:00Z"));
        when(resolver.siblingGroups(any())).thenAnswer(inv -> {
            java.util.Collection<String> known = inv.getArgument(0);
            assertTrue(known.contains("legacy"));
            return Map.of("host:5432", List.of("prod", "legacy"));
        });

        assertEquals(1, task.mergeDuplicates(raw));
        assertTrue(raw.find("prod", "acme").orElseThrow().isProtectedFlag());
    }
}
