package br.com.fzdevx.domain.model;

public record HostMemoryStatus(
        boolean supported,
        boolean enabled,
        boolean available,
        long totalMb,
        long usedMb,
        long availableMb,
        long thresholdMb
) {}
