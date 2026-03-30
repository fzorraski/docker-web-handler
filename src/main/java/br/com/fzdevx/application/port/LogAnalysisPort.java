package br.com.fzdevx.application.port;

import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.LogPreset;

import java.nio.file.Path;
import java.util.List;

public interface LogAnalysisPort {

    LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs);
}
