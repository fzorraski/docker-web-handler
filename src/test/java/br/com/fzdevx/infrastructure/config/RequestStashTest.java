package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.dto.AnalyzeLogFileRequest;
import br.com.fzdevx.application.dto.RunContainerRequest;
import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.application.dto.CreateSnapshotRequest;
import br.com.fzdevx.application.dto.PruneImagesRequest;
import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.domain.model.LogPreset;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestStashTest {

    private RequestStash stash;

    @BeforeEach
    void setUp() {
        stash = new RequestStash();
    }

    // ---- RunContainerRequest ----

    @Test
    void stashAndRetrieve_runContainer() {
        RunContainerRequest req = new RunContainerRequest();
        req.setRepository("postgres");
        String ticket = stash.stash(req);

        assertNotNull(ticket);
        RunContainerRequest retrieved = stash.retrieve(ticket);
        assertEquals("postgres", retrieved.getRepository());
    }

    @Test
    void retrieve_consumesTicket() {
        RunContainerRequest req = new RunContainerRequest();
        String ticket = stash.stash(req);
        assertNotNull(stash.retrieve(ticket));
        assertNull(stash.retrieve(ticket));
    }

    @Test
    void retrieve_unknownTicket_returnsNull() {
        assertNull(stash.retrieve("nonexistent"));
    }

    // ---- RestoreDumpRequest ----

    @Test
    void stashAndRetrieve_restoreDump() {
        RestoreDumpRequest req = new RestoreDumpRequest();
        req.setDumpId("dump-123");
        String ticket = stash.stashRestore(req);

        RestoreDumpRequest retrieved = stash.retrieveRestore(ticket);
        assertEquals("dump-123", retrieved.getDumpId());
    }

    @Test
    void retrieveRestore_consumesTicket() {
        String ticket = stash.stashRestore(new RestoreDumpRequest());
        assertNotNull(stash.retrieveRestore(ticket));
        assertNull(stash.retrieveRestore(ticket));
    }

    // ---- CreateSnapshotRequest ----

    @Test
    void stashAndRetrieve_snapshot() {
        CreateSnapshotRequest req = new CreateSnapshotRequest();
        req.setRepository("postgres");
        String ticket = stash.stashSnapshot(req);

        CreateSnapshotRequest retrieved = stash.retrieveSnapshot(ticket);
        assertEquals("postgres", retrieved.getRepository());
    }

    // ---- PruneImagesRequest ----

    @Test
    void stashAndRetrieve_prune() {
        PruneImagesRequest req = new PruneImagesRequest();
        req.setMinDays(30);
        String ticket = stash.stashPrune(req);

        PruneImagesRequest retrieved = stash.retrievePrune(ticket);
        assertEquals(30, retrieved.getMinDays());
    }

    // ---- RunMigrationRequest ----

    @Test
    void stashAndRetrieve_migration() {
        RunMigrationRequest req = new RunMigrationRequest();
        req.setRepository("postgres");
        String ticket = stash.stashMigration(req);

        RunMigrationRequest retrieved = stash.retrieveMigration(ticket);
        assertEquals("postgres", retrieved.getRepository());
    }

    // ---- Terminal ----

    @Test
    void stashAndRetrieve_terminal() {
        String ticket = stash.stashTerminal("abc123def4");
        assertEquals("abc123def4", stash.retrieveTerminal(ticket));
    }

    @Test
    void retrieveTerminal_consumesTicket() {
        String ticket = stash.stashTerminal("abc123def4");
        assertNotNull(stash.retrieveTerminal(ticket));
        assertNull(stash.retrieveTerminal(ticket));
    }

    // ---- Uniqueness ----

    @Test
    void tickets_areUnique() {
        String t1 = stash.stash(new RunContainerRequest());
        String t2 = stash.stash(new RunContainerRequest());
        assertNotEquals(t1, t2);
    }

    // ---- Cross-stash isolation ----

    @Test
    void differentStashes_areSeparate() {
        String ticket = stash.stash(new RunContainerRequest());
        assertNull(stash.retrieveRestore(ticket));
        assertNull(stash.retrieveSnapshot(ticket));
        assertNull(stash.retrievePrune(ticket));
        assertNull(stash.retrieveMigration(ticket));
        assertNull(stash.retrieveTerminal(ticket));
        assertNull(stash.retrieveLogAnalysis(ticket));
        // Original should still be there
        assertNotNull(stash.retrieve(ticket));
    }

    // ---- Log Analysis Stash ----

    @Test
    void stashLogAnalysis_returnsTicket() {
        var request = new AnalyzeLogFileRequest(
                List.of(Path.of("/tmp/test.log")), List.of(Path.of("/tmp")),
                List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());
        String ticket = stash.stashLogAnalysis(request);

        assertNotNull(ticket);
        assertFalse(ticket.isBlank());
    }

    @Test
    void retrieveLogAnalysis_returnsAndConsumesTicket() {
        var request = new AnalyzeLogFileRequest(
                List.of(Path.of("/tmp/test.log")), List.of(Path.of("/tmp")),
                List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());
        String ticket = stash.stashLogAnalysis(request);

        AnalyzeLogFileRequest retrieved = stash.retrieveLogAnalysis(ticket);
        assertNotNull(retrieved);
        assertEquals("test.log", retrieved.getFilenames().getFirst());
        assertEquals(1000, retrieved.getSlowThresholdMs());

        // Consumed — second retrieval returns null
        assertNull(stash.retrieveLogAnalysis(ticket));
    }

    @Test
    void retrieveLogAnalysis_unknownTicket_returnsNull() {
        assertNull(stash.retrieveLogAnalysis("nonexistent"));
    }

    @Test
    void logAnalysisStash_isolatedFromOtherStashes() {
        var request = new AnalyzeLogFileRequest(
                List.of(Path.of("/tmp/test.log")), List.of(Path.of("/tmp")),
                List.of("test.log"), LogPreset.WILDFLY, 1000, AnalysisOptions.all());
        String ticket = stash.stashLogAnalysis(request);

        assertNull(stash.retrieve(ticket));
        assertNull(stash.retrieveRestore(ticket));
        assertNotNull(stash.retrieveLogAnalysis(ticket));
    }
}
