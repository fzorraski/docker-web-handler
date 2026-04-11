/**
 * Layer: application/usecase
 * SOLID: S (single responsibility — performance insights and bucket endpoint queries),
 *        D (depends on domain shared calculator, no infrastructure)
 * Behavior: identical to original LogAnalyzerController#getPerformanceInsights and #getBucketEndpoints
 */
package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.PerformanceInsights;
import br.com.fzdevx.domain.shared.PerformanceInsightsCalculator;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class GetPerformanceInsightsUseCase {

    public PerformanceInsights computeInsights(List<ApiCallPair> allCalls, String endpoint,
                                               LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd) {
        var calls = allCalls;
        if (endpoint != null && !endpoint.isBlank()) {
            calls = calls.stream().filter(c -> endpoint.equals(c.endpoint())).toList();
        }
        if (calls.isEmpty()) {
            return new PerformanceInsights(List.of(), List.of(), "1m", 0);
        }
        return PerformanceInsightsCalculator.compute(calls, timeRangeStart, timeRangeEnd);
    }

    public List<PerformanceInsights.EndpointBucket> computeBucketEndpoints(
            List<ApiCallPair> allCalls, String endpoint,
            LocalDateTime timeRangeStart, LocalDateTime timeRangeEnd,
            String timestamp, int limit) {
        var calls = allCalls;
        if (endpoint != null && !endpoint.isBlank()) {
            calls = calls.stream().filter(c -> endpoint.equals(c.endpoint())).toList();
        }
        return PerformanceInsightsCalculator.computeBucketEndpoints(
                calls, timeRangeStart, timeRangeEnd, timestamp, limit);
    }
}
