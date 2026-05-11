package br.com.fzdevx.application.dto;

import java.time.LocalDateTime;

public record ApiCallQuery(
        String endpoint,
        String thread,
        Long minDuration,
        Long maxDuration,
        boolean slowOnly,
        boolean slowConnectionOnly,
        Long minConnectionDelay,
        Long maxConnectionDelay,
        String search,
        String exclude,
        LocalDateTime timeFrom,
        LocalDateTime timeTo,
        String sort,
        String sortDir,
        int page,
        int size
) {
    public static ApiCallQuery of(String endpoint, String thread, Long minDuration, Long maxDuration,
                                  boolean slowOnly, boolean slowConnectionOnly,
                                  Long minConnectionDelay, Long maxConnectionDelay,
                                  String search, String exclude,
                                  LocalDateTime timeFrom, LocalDateTime timeTo,
                                  String sort, String sortDir, int page, int size) {
        return new ApiCallQuery(endpoint, thread, minDuration, maxDuration, slowOnly, slowConnectionOnly,
                minConnectionDelay, maxConnectionDelay, search, exclude, timeFrom, timeTo,
                sort, sortDir, page, size);
    }
}
