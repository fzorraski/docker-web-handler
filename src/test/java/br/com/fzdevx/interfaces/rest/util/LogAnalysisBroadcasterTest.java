package br.com.fzdevx.interfaces.rest.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogAnalysisBroadcasterTest {

    private LogAnalysisBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new LogAnalysisBroadcaster();
    }

    // ---- Viewer Tracking ----

    @Test
    void setViewing_tracksClientToken() {
        broadcaster.setViewing("client-1", "analysis-abc");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertEquals(1L, counts.get("analysis-abc"));
    }

    @Test
    void setViewing_multipleClientsOnSameAnalysis() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-2", "analysis-abc");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertEquals(2L, counts.get("analysis-abc"));
    }

    @Test
    void setViewing_differentAnalyses() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-2", "analysis-xyz");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertEquals(1L, counts.get("analysis-abc"));
        assertEquals(1L, counts.get("analysis-xyz"));
    }

    @Test
    void setViewing_switchAnalysis_updatesCount() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-1", "analysis-xyz");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertNull(counts.get("analysis-abc"));
        assertEquals(1L, counts.get("analysis-xyz"));
    }

    @Test
    void setViewing_nullAnalysisId_removesViewer() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-1", null);

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertTrue(counts.isEmpty());
    }

    @Test
    void setViewing_blankAnalysisId_removesViewer() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-1", "");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertTrue(counts.isEmpty());
    }

    @Test
    void setViewing_blankClientToken_ignored() {
        broadcaster.setViewing("", "analysis-abc");

        // No effect since empty token is ignored in the controller
        // The broadcaster itself accepts it — this tests the data layer
        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertEquals(1L, counts.get("analysis-abc"));
    }

    @Test
    void getViewerCounts_emptyWhenNoViewers() {
        assertTrue(broadcaster.getViewerCounts().isEmpty());
    }

    // ---- broadcastDeleted ----

    @Test
    void broadcastDeleted_removesViewersOfDeletedAnalysis() {
        broadcaster.setViewing("client-1", "analysis-abc");
        broadcaster.setViewing("client-2", "analysis-abc");
        broadcaster.setViewing("client-3", "analysis-xyz");

        broadcaster.broadcastDeleted("analysis-abc");

        Map<String, Long> counts = broadcaster.getViewerCounts();
        assertNull(counts.get("analysis-abc"));
        assertEquals(1L, counts.get("analysis-xyz"));
    }

    @Test
    void broadcastDeleted_nonexistentAnalysis_noEffect() {
        broadcaster.setViewing("client-1", "analysis-abc");

        broadcaster.broadcastDeleted("nonexistent");

        assertEquals(1L, broadcaster.getViewerCounts().get("analysis-abc"));
    }

    // ---- Active Analysis Tracking ----

    @Test
    void broadcastStarted_tracksActiveAnalysis() {
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));

        var active = broadcaster.getActiveAnalyses();
        assertEquals(1, active.size());
        assertEquals(1, active.get("server.log"));
    }

    @Test
    void broadcastStarted_tracksIndividualFilenames() {
        broadcaster.broadcastStarted("", "a.log, b.log", List.of("a.log", "b.log"));

        var filenames = broadcaster.getActiveFilenames();
        assertEquals(2, filenames.size());
        assertTrue(filenames.contains("a.log"));
        assertTrue(filenames.contains("b.log"));
    }

    @Test
    void broadcastCompleted_removesActiveAnalysis() {
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));
        broadcaster.broadcastCompleted("", "server.log", "analysis-123", List.of("server.log"));

        assertTrue(broadcaster.getActiveAnalyses().isEmpty());
        assertTrue(broadcaster.getActiveFilenames().isEmpty());
    }

    @Test
    void broadcastCompleted_afterCancel_removesActiveAnalysis() {
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));
        broadcaster.broadcastCompleted("", "server.log", "", List.of("server.log"));

        assertTrue(broadcaster.getActiveAnalyses().isEmpty());
        assertTrue(broadcaster.getActiveFilenames().isEmpty());
    }

    @Test
    void multipleActiveAnalyses_trackedIndependently() {
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));
        broadcaster.broadcastStarted("", "app.log", List.of("app.log"));

        var active = broadcaster.getActiveAnalyses();
        assertEquals(2, active.size());

        broadcaster.broadcastCompleted("", "server.log", "id-1", List.of("server.log"));

        active = broadcaster.getActiveAnalyses();
        assertEquals(1, active.size());
        assertEquals(1, active.get("app.log"));
    }

    @Test
    void concurrentUploads_sameFilename_refCounted() {
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));
        broadcaster.broadcastStarted("", "server.log", List.of("server.log"));

        assertEquals(2, broadcaster.getActiveAnalyses().get("server.log"));
        assertEquals(1, broadcaster.getActiveFilenames().size());

        broadcaster.broadcastCompleted("", "server.log", "id-1", List.of("server.log"));
        assertEquals(1, broadcaster.getActiveAnalyses().get("server.log"));

        broadcaster.broadcastCompleted("", "server.log", "id-2", List.of("server.log"));
        assertTrue(broadcaster.getActiveAnalyses().isEmpty());
        assertTrue(broadcaster.getActiveFilenames().isEmpty());
    }

    @Test
    void filenameWithComma_trackedCorrectly() {
        // Filename containing ", " — must not be split incorrectly
        broadcaster.broadcastStarted("", "server, production.log", List.of("server, production.log"));

        var filenames = broadcaster.getActiveFilenames();
        assertEquals(1, filenames.size());
        assertTrue(filenames.contains("server, production.log"));
    }

    @Test
    void getActiveAnalyses_emptyByDefault() {
        assertTrue(broadcaster.getActiveAnalyses().isEmpty());
        assertTrue(broadcaster.getActiveFilenames().isEmpty());
    }
}
