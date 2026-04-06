package br.com.fzdevx.interfaces.rest.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
