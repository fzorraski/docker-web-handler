package br.com.fzdevx.interfaces.rest.util;

import br.com.fzdevx.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisSummaryMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 10, 0, 0);

    private ApiCallPair callWithUpstream(long upstreamMs) {
        return new ApiCallPair("/api/test", null, "t-1", NOW, NOW.plusSeconds(1),
                200, upstreamMs, false, null, null, 1, 2, "test.log", false);
    }

    private LogAnalysis buildAnalysis(List<ApiCallPair> apiCalls) {
        return new LogAnalysis(
                List.of(new LogAnalysis.SourceFile("test.log", 1024)),
                10, NOW, NOW.plusMinutes(5),
                List.of("t-1"), List.of("/api/test"),
                apiCalls, List.of(), Map.of("INFO", 10), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    // ---- hasConnectionDelay ----

    @Test
    void toSummaryMap_hasConnectionDelay_trueWhenUpstreamPresent() {
        LogAnalysis analysis = buildAnalysis(List.of(callWithUpstream(100)));
        Map<String, Object> map = AnalysisSummaryMapper.toSummaryMap(analysis);
        assertEquals(true, map.get("hasConnectionDelay"));
    }

    @Test
    void toSummaryMap_hasConnectionDelay_falseWhenNoUpstream() {
        LogAnalysis analysis = buildAnalysis(List.of(callWithUpstream(-1)));
        Map<String, Object> map = AnalysisSummaryMapper.toSummaryMap(analysis);
        assertEquals(false, map.get("hasConnectionDelay"));
    }

    @Test
    void toSummaryMap_hasConnectionDelay_falseWhenEmptyApiCalls() {
        LogAnalysis analysis = buildAnalysis(List.of());
        Map<String, Object> map = AnalysisSummaryMapper.toSummaryMap(analysis);
        assertEquals(false, map.get("hasConnectionDelay"));
    }

    @Test
    void toSummaryMap_hasConnectionDelay_trueWhenMixed() {
        LogAnalysis analysis = buildAnalysis(List.of(callWithUpstream(-1), callWithUpstream(50)));
        Map<String, Object> map = AnalysisSummaryMapper.toSummaryMap(analysis);
        assertEquals(true, map.get("hasConnectionDelay"));
    }

    // ---- presetToMap upstreamDurationField ----

    @Test
    void presetToMap_nginxPreset_includesUpstreamField() {
        Map<String, Object> map = AnalysisSummaryMapper.presetToMap(LogPreset.NGINX);
        assertEquals("urt", map.get("upstreamDurationField"));
    }

    @Test
    void presetToMap_wildflyPreset_upstreamFieldIsNull() {
        Map<String, Object> map = AnalysisSummaryMapper.presetToMap(LogPreset.WILDFLY);
        assertNull(map.get("upstreamDurationField"));
    }

    @Test
    void presetToMap_customPreset_upstreamFieldIsNull() {
        Map<String, Object> map = AnalysisSummaryMapper.presetToMap(LogPreset.CUSTOM);
        assertNull(map.get("upstreamDurationField"));
    }
}
