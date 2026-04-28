package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.DuplicateDetectionResponse;
import br.com.fzdevx.domain.model.DuplicateGroup;
import br.com.fzdevx.domain.model.DuplicateGroup.DuplicateCall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DuplicateRequestDetector {

    private static final int PAYLOAD_HASH_THRESHOLD = 10_240;
    private static final int PAYLOAD_PREVIEW_MAX = 5000;
    private static final String NULL_SENTINEL = "<null>";

    private DuplicateRequestDetector() {}

    public static DuplicateDetectionResponse detect(List<ApiCallPair> apiCalls, String matchBy,
                                                     int timeWindowSeconds, int minOccurrences,
                                                     int maxCallsPerGroup) {
        if (apiCalls == null || apiCalls.isEmpty()) {
            return emptyResponse(matchBy, timeWindowSeconds, minOccurrences);
        }

        Function<ApiCallPair, String> keyExtractor = buildKeyExtractor(matchBy);

        // Single-pass grouping
        Map<String, List<ApiCallPair>> grouped = apiCalls.stream()
                .collect(Collectors.groupingBy(keyExtractor, LinkedHashMap::new, Collectors.toList()));

        // Sort each group by timestamp and optionally split by time window
        List<List<ApiCallPair>> candidateGroups = new ArrayList<>();
        for (List<ApiCallPair> group : grouped.values()) {
            group.sort(Comparator.comparing(ApiCallPair::requestTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));

            if (timeWindowSeconds > 0) {
                candidateGroups.addAll(splitByTimeWindow(group, timeWindowSeconds));
            } else {
                candidateGroups.add(group);
            }
        }

        // Filter by minOccurrences
        List<List<ApiCallPair>> duplicateGroups = candidateGroups.stream()
                .filter(g -> g.size() >= minOccurrences)
                .toList();

        if (duplicateGroups.isEmpty()) {
            return emptyResponse(matchBy, timeWindowSeconds, minOccurrences);
        }

        // Build DuplicateGroup records
        List<DuplicateGroup> builtGroups = duplicateGroups.stream()
                .map(g -> buildGroup(g, maxCallsPerGroup))
                .sorted(Comparator.comparingInt(DuplicateGroup::occurrenceCount).reversed())
                .toList();

        // Compute summary stats from full list (before cap)
        int totalGroups = builtGroups.size();
        int totalDuplicateCalls = builtGroups.stream().mapToInt(g -> g.occurrenceCount() - 1).sum();

        DuplicateGroup mostDuplicated = builtGroups.getFirst();
        String mostDuplicatedEndpoint = mostDuplicated.endpoint();
        int mostDuplicatedCount = mostDuplicated.occurrenceCount();

        long avgTimeBetween = computeWeightedAvgTimeBetween(builtGroups);

        return new DuplicateDetectionResponse(
                matchBy, timeWindowSeconds, minOccurrences,
                totalGroups, totalDuplicateCalls,
                mostDuplicatedEndpoint, mostDuplicatedCount,
                avgTimeBetween,
                builtGroups
        );
    }

    private static Function<ApiCallPair, String> buildKeyExtractor(String matchBy) {
        if ("endpoint-only".equals(matchBy)) {
            return ApiCallPair::endpoint;
        }
        // Default: endpoint+payload
        return call -> call.endpoint() + "|" + normalizePayload(call.requestPayload());
    }

    private static String normalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return NULL_SENTINEL;
        }
        String trimmed = payload.strip();
        if (trimmed.length() > PAYLOAD_HASH_THRESHOLD) {
            return hashPayload(trimmed);
        }
        return trimmed;
    }

    private static String hashPayload(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in the JDK
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static List<List<ApiCallPair>> splitByTimeWindow(List<ApiCallPair> sortedGroup, int windowSeconds) {
        if (sortedGroup.isEmpty()) return List.of();
        List<List<ApiCallPair>> subGroups = new ArrayList<>();
        List<ApiCallPair> current = new ArrayList<>();
        current.add(sortedGroup.getFirst());

        for (int i = 1; i < sortedGroup.size(); i++) {
            ApiCallPair prev = sortedGroup.get(i - 1);
            ApiCallPair curr = sortedGroup.get(i);

            if (prev.requestTimestamp() != null && curr.requestTimestamp() != null) {
                long gapSeconds = Duration.between(prev.requestTimestamp(), curr.requestTimestamp()).toSeconds();
                if (gapSeconds > windowSeconds) {
                    subGroups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(curr);
        }
        subGroups.add(current);
        return subGroups;
    }

    private static DuplicateGroup buildGroup(List<ApiCallPair> calls, int maxCallsPerGroup) {
        ApiCallPair first = calls.getFirst();
        String endpoint = first.endpoint();
        String payloadPreview = buildPayloadPreview(first.requestPayload());
        int occurrenceCount = calls.size();

        LocalDateTime firstOccurrence = calls.stream()
                .map(ApiCallPair::requestTimestamp)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime lastOccurrence = calls.stream()
                .map(ApiCallPair::requestTimestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        long timeSpanMs = (firstOccurrence != null && lastOccurrence != null)
                ? Duration.between(firstOccurrence, lastOccurrence).toMillis()
                : 0;

        long avgTimeBetweenMs = computeAvgTimeBetween(calls);

        List<DuplicateCall> duplicateCalls = calls.stream()
                .limit(maxCallsPerGroup)
                .map(c -> new DuplicateCall(
                        c.correlationId(), c.thread(), c.requestTimestamp(),
                        c.durationMs(), c.requestLineNumber(), c.responseLineNumber(),
                        c.sourceFile(), c.slow()
                ))
                .toList();

        return new DuplicateGroup(
                endpoint, payloadPreview, occurrenceCount,
                firstOccurrence, lastOccurrence, timeSpanMs, avgTimeBetweenMs,
                duplicateCalls
        );
    }

    private static String buildPayloadPreview(String payload) {
        if (payload == null || payload.isBlank()) {
            return "[empty]";
        }
        String trimmed = payload.strip();
        if (trimmed.length() <= PAYLOAD_PREVIEW_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, PAYLOAD_PREVIEW_MAX) + "...";
    }

    private static long computeAvgTimeBetween(List<ApiCallPair> sortedCalls) {
        if (sortedCalls.size() < 2) return 0;

        long totalGapMs = 0;
        int gapCount = 0;
        for (int i = 1; i < sortedCalls.size(); i++) {
            LocalDateTime prev = sortedCalls.get(i - 1).requestTimestamp();
            LocalDateTime curr = sortedCalls.get(i).requestTimestamp();
            if (prev != null && curr != null) {
                totalGapMs += Duration.between(prev, curr).toMillis();
                gapCount++;
            }
        }
        return gapCount > 0 ? totalGapMs / gapCount : 0;
    }

    private static long computeWeightedAvgTimeBetween(List<DuplicateGroup> groups) {
        long totalWeightedMs = 0;
        int totalWeight = 0;
        for (DuplicateGroup g : groups) {
            if (g.occurrenceCount() > 1 && g.avgTimeBetweenMs() > 0) {
                int weight = g.occurrenceCount() - 1;
                totalWeightedMs += g.avgTimeBetweenMs() * weight;
                totalWeight += weight;
            }
        }
        return totalWeight > 0 ? totalWeightedMs / totalWeight : 0;
    }

    private static DuplicateDetectionResponse emptyResponse(String matchBy, int timeWindowSeconds, int minOccurrences) {
        return new DuplicateDetectionResponse(
                matchBy, timeWindowSeconds, minOccurrences,
                0, 0, "", 0, 0, List.of()
        );
    }
}
