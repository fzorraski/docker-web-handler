package br.com.fzdevx.domain.model;

import java.util.List;

public record CustomFieldResult(
        String fieldName,
        int matchCount,
        boolean countOnly,
        List<CustomFieldMatch> matches
) {}
