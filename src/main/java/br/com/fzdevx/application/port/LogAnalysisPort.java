package br.com.fzdevx.application.port;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.LogPreset;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public interface LogAnalysisPort {

    LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                        AnalysisOptions options);

    LogAnalysis analyze(List<Path> files, List<String> filenames, LogPreset preset, int slowThresholdMs,
                        AnalysisOptions options, Consumer<ContainerEvent> progressSink, AtomicBoolean cancelled);
}
