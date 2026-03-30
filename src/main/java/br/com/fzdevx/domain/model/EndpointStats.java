package br.com.fzdevx.domain.model;

public record EndpointStats(
        String endpoint,
        int callCount,
        double avgDurationMs,
        long minDurationMs,
        long maxDurationMs,
        long p95DurationMs,
        int slowCount
) {}
