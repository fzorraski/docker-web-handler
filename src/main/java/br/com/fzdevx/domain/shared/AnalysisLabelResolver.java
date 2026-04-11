package br.com.fzdevx.domain.shared;

import br.com.fzdevx.domain.model.LogAnalysis;

public final class AnalysisLabelResolver {

    private AnalysisLabelResolver() {
    }

    public static String resolve(LogAnalysis analysis) {
        return analysis.getLabel() != null ? analysis.getLabel()
                : analysis.getSourceFiles().stream()
                        .map(LogAnalysis.SourceFile::filename)
                        .findFirst()
                        .orElse("analysis");
    }

    public static String toSafeFilenamePrefix(LogAnalysis analysis) {
        return resolve(analysis).replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
