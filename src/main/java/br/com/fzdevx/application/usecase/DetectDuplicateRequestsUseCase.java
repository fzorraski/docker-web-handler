package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.ApiCallPair;
import br.com.fzdevx.domain.model.DuplicateDetectionResponse;
import br.com.fzdevx.domain.shared.DuplicateRequestDetector;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class DetectDuplicateRequestsUseCase {

    public static final Set<String> VALID_MATCH_BY = Set.of("endpoint+payload", "endpoint-only");

    private static final int DEFAULT_MAX_CALLS_PER_GROUP = 100;

    public DuplicateDetectionResponse detect(List<ApiCallPair> apiCalls, String matchBy,
                                              int timeWindowSeconds, int minOccurrences) {
        return DuplicateRequestDetector.detect(
                apiCalls, matchBy, timeWindowSeconds, minOccurrences, DEFAULT_MAX_CALLS_PER_GROUP);
    }
}
