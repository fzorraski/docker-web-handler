package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.EndpointStats;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class EndpointStatsCalculator {

    private EndpointStatsCalculator() {}

    public static List<EndpointStats> compute(List<ApiCallPair> apiCalls, int slowThresholdMs) {
        Map<String, List<ApiCallPair>> byEndpoint = apiCalls.stream()
                .collect(Collectors.groupingBy(ApiCallPair::endpoint, LinkedHashMap::new, Collectors.toList()));

        return byEndpoint.entrySet().stream().map(e -> {
            String endpoint = e.getKey();
            List<ApiCallPair> calls = e.getValue();
            long[] durations = calls.stream().mapToLong(ApiCallPair::durationMs).sorted().toArray();
            double avg = calls.stream().mapToLong(ApiCallPair::durationMs).average().orElse(0);
            long min = durations.length > 0 ? durations[0] : 0;
            long max = durations.length > 0 ? durations[durations.length - 1] : 0;
            int p95Index = (int) Math.ceil(durations.length * 0.95) - 1;
            long p95 = durations.length > 0 ? durations[Math.max(0, p95Index)] : 0;
            int slowCount = (int) calls.stream().filter(c -> c.durationMs() >= slowThresholdMs).count();
            return new EndpointStats(endpoint, calls.size(), avg, min, max, p95, slowCount);
        })
        .sorted(Comparator.comparingInt(EndpointStats::callCount).reversed())
        .toList();
    }
}
