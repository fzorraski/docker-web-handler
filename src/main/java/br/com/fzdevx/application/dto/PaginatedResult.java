/**
 * Layer: application/dto
 * SOLID: S (single responsibility — pagination data carrier)
 * Behavior: replaces ad-hoc Map.of("data", ..., "total", ..., "page", ..., "size", ...)
 */
package br.com.fzdevx.application.dto;

import java.util.List;
import java.util.Map;

public record PaginatedResult<T>(List<T> data, int total, int page, int size) {

    public static <T> PaginatedResult<T> of(List<T> all, int page, int size) {
        page = Math.max(0, page);
        size = Math.clamp(size, 1, 15000);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        return new PaginatedResult<>(List.copyOf(all.subList(from, to)), total, page, size);
    }

    public Map<String, Object> toMap() {
        return Map.of("data", data, "total", total, "page", page, "size", size);
    }
}
