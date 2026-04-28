package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.DuplicateDetectionResponse;
import br.com.fzdevx.domain.model.DuplicateGroup;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateRequestDetectorTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 4, 27, 10, 0, 0);

    private ApiCallPair call(String endpoint, String payload, LocalDateTime requestTime, long durationMs) {
        return new ApiCallPair(
                endpoint, null, "thread-1",
                requestTime,
                requestTime.plusNanos(durationMs * 1_000_000),
                durationMs,
                payload, null,
                1, 2, "test.log", false
        );
    }

    private ApiCallPair call(String endpoint, String payload, LocalDateTime requestTime,
                             long durationMs, String thread) {
        return new ApiCallPair(
                endpoint, null, thread,
                requestTime,
                requestTime.plusNanos(durationMs * 1_000_000),
                durationMs,
                payload, null,
                1, 2, "test.log", false
        );
    }

    // ---- Empty / null input ----

    @Test
    void nullInputReturnsEmptyResponse() {
        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                null, "endpoint+payload", 0, 2, 100);

        assertEquals(0, result.totalGroups());
        assertEquals(0, result.totalDuplicateCalls());
        assertTrue(result.groups().isEmpty());
    }

    @Test
    void emptyListReturnsEmptyResponse() {
        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                List.of(), "endpoint+payload", 0, 2, 100);

        assertEquals(0, result.totalGroups());
        assertTrue(result.groups().isEmpty());
    }

    // ---- No duplicates ----

    @Test
    void allUniqueCallsReturnsNoGroups() {
        var calls = List.of(
                call("endpointA", "payload1", BASE, 100),
                call("endpointB", "payload2", BASE.plusSeconds(1), 200),
                call("endpointA", "payload3", BASE.plusSeconds(2), 150)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(0, result.totalGroups());
        assertTrue(result.groups().isEmpty());
    }

    // ---- Endpoint + payload matching ----

    @Test
    void detectsDuplicatesByEndpointAndPayload() {
        var calls = List.of(
                call("endpointA", "payload1", BASE, 100),
                call("endpointA", "payload1", BASE.plusSeconds(5), 200),
                call("endpointA", "payload1", BASE.plusSeconds(10), 150),
                call("endpointA", "payload2", BASE.plusSeconds(15), 300)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        assertEquals(2, result.totalDuplicateCalls()); // 3 occurrences - 1 = 2 extra
        assertEquals("endpointA", result.mostDuplicatedEndpoint());
        assertEquals(3, result.mostDuplicatedCount());

        DuplicateGroup group = result.groups().getFirst();
        assertEquals("endpointA", group.endpoint());
        assertEquals(3, group.occurrenceCount());
        assertEquals(3, group.calls().size());
        assertEquals("payload1", group.payloadPreview());
    }

    @Test
    void differentEndpointsSamePayloadNotGrouped() {
        var calls = List.of(
                call("endpointA", "payload1", BASE, 100),
                call("endpointB", "payload1", BASE.plusSeconds(1), 200)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(0, result.totalGroups());
    }

    // ---- Endpoint-only matching ----

    @Test
    void endpointOnlyGroupsDifferentPayloads() {
        var calls = List.of(
                call("endpointA", "payload1", BASE, 100),
                call("endpointA", "payload2", BASE.plusSeconds(5), 200),
                call("endpointA", "payload3", BASE.plusSeconds(10), 150)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint-only", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        assertEquals(3, result.groups().getFirst().occurrenceCount());
    }

    // ---- minOccurrences filtering ----

    @Test
    void groupsBelowMinOccurrencesExcluded() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(1), 200),
                call("endpointB", "p2", BASE.plusSeconds(2), 150),
                call("endpointB", "p2", BASE.plusSeconds(3), 250),
                call("endpointB", "p2", BASE.plusSeconds(4), 350)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 3, 100);

        assertEquals(1, result.totalGroups());
        assertEquals("endpointB", result.groups().getFirst().endpoint());
        assertEquals(3, result.groups().getFirst().occurrenceCount());
    }

    // ---- Time window splitting ----

    @Test
    void timeWindowSplitsGroupsAtGaps() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(2), 200),
                // Gap > 60 seconds
                call("endpointA", "p1", BASE.plusMinutes(5), 150),
                call("endpointA", "p1", BASE.plusMinutes(5).plusSeconds(3), 250)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 60, 2, 100);

        assertEquals(2, result.totalGroups());
        // Both groups have 2 occurrences
        assertEquals(2, result.groups().get(0).occurrenceCount());
        assertEquals(2, result.groups().get(1).occurrenceCount());
    }

    @Test
    void timeWindowZeroDoesNotSplit() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusHours(2), 200)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        assertEquals(2, result.groups().getFirst().occurrenceCount());
    }

    // ---- Summary stats ----

    @Test
    void summaryStatsCorrect() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(10), 200),
                call("endpointB", "p2", BASE.plusSeconds(20), 150),
                call("endpointB", "p2", BASE.plusSeconds(30), 250),
                call("endpointB", "p2", BASE.plusSeconds(40), 350)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(2, result.totalGroups());
        assertEquals(3, result.totalDuplicateCalls()); // (2-1) + (3-1) = 3
        assertEquals("endpointB", result.mostDuplicatedEndpoint());
        assertEquals(3, result.mostDuplicatedCount());
        assertTrue(result.avgTimeBetweenDuplicatesMs() > 0);
    }

    @Test
    void groupsSortedByOccurrenceDescending() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(1), 200),
                call("endpointB", "p2", BASE.plusSeconds(2), 150),
                call("endpointB", "p2", BASE.plusSeconds(3), 250),
                call("endpointB", "p2", BASE.plusSeconds(4), 350)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals("endpointB", result.groups().get(0).endpoint());
        assertEquals("endpointA", result.groups().get(1).endpoint());
    }

    // ---- Null payload handling ----

    @Test
    void nullPayloadsGroupedTogether() {
        var calls = List.of(
                call("endpointA", null, BASE, 100),
                call("endpointA", null, BASE.plusSeconds(5), 200),
                call("endpointA", "payload1", BASE.plusSeconds(10), 150)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        assertEquals(2, result.groups().getFirst().occurrenceCount());
        assertEquals("[empty]", result.groups().getFirst().payloadPreview());
    }

    @Test
    void blankPayloadsTreatedAsNull() {
        var calls = List.of(
                call("endpointA", "   ", BASE, 100),
                call("endpointA", null, BASE.plusSeconds(5), 200)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
    }

    // ---- Payload preview truncation ----

    @Test
    void payloadPreviewTruncatedAt5000Chars() {
        String longPayload = "x".repeat(6000);
        var calls = List.of(
                call("endpointA", longPayload, BASE, 100),
                call("endpointA", longPayload, BASE.plusSeconds(5), 200)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        String preview = result.groups().getFirst().payloadPreview();
        assertEquals(5003, preview.length()); // 5000 + "..."
        assertTrue(preview.endsWith("..."));
    }

    // ---- maxGroups cap ----

    @Test
    void multipleGroupsAllReturned() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(1), 200),
                call("endpointB", "p2", BASE.plusSeconds(2), 150),
                call("endpointB", "p2", BASE.plusSeconds(3), 250),
                call("endpointC", "p3", BASE.plusSeconds(4), 350),
                call("endpointC", "p3", BASE.plusSeconds(5), 450)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(3, result.totalGroups());
        assertEquals(3, result.groups().size());
    }

    // ---- maxCallsPerGroup cap ----

    @Test
    void maxCallsPerGroupCapsCallsListButKeepsAccurateCount() {
        var calls = new java.util.ArrayList<ApiCallPair>();
        for (int i = 0; i < 10; i++) {
            calls.add(call("endpointA", "p1", BASE.plusSeconds(i), 100));
        }

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 3);

        assertEquals(1, result.totalGroups());
        DuplicateGroup group = result.groups().getFirst();
        assertEquals(10, group.occurrenceCount()); // accurate count
        assertEquals(3, group.calls().size()); // capped
    }

    // ---- Time span and avg gap ----

    @Test
    void timeSpanAndAvgGapComputedCorrectly() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100),
                call("endpointA", "p1", BASE.plusSeconds(10), 200),
                call("endpointA", "p1", BASE.plusSeconds(30), 150)
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        DuplicateGroup group = result.groups().getFirst();
        assertEquals(30_000, group.timeSpanMs());
        assertEquals(15_000, group.avgTimeBetweenMs()); // (10s + 20s) / 2 = 15s
    }

    // ---- Echo-back of configuration ----

    @Test
    void responseEchoesBackConfiguration() {
        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                List.of(), "endpoint-only", 120, 5, 100);

        assertEquals("endpoint-only", result.matchBy());
        assertEquals(120, result.timeWindowSeconds());
        assertEquals(5, result.minOccurrences());
    }

    // ---- Multiple threads, same endpoint+payload ----

    @Test
    void differentThreadsSameEndpointPayloadGroupedTogether() {
        var calls = List.of(
                call("endpointA", "p1", BASE, 100, "thread-1"),
                call("endpointA", "p1", BASE.plusSeconds(1), 200, "thread-2")
        );

        DuplicateDetectionResponse result = DuplicateRequestDetector.detect(
                calls, "endpoint+payload", 0, 2, 100);

        assertEquals(1, result.totalGroups());
        assertEquals(2, result.groups().getFirst().occurrenceCount());
    }
}
