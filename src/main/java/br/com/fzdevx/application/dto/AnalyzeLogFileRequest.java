package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.LogPreset;

import java.nio.file.Path;
import java.util.List;

public class AnalyzeLogFileRequest {

    private final List<Path> tempFiles;
    private final List<Path> tempDirs;
    private final List<String> filenames;
    private final LogPreset preset;
    private final int slowThresholdMs;
    private final AnalysisOptions options;

    public AnalyzeLogFileRequest(List<Path> tempFiles, List<Path> tempDirs, List<String> filenames,
                                  LogPreset preset, int slowThresholdMs, AnalysisOptions options) {
        this.tempFiles = tempFiles;
        this.tempDirs = tempDirs;
        this.filenames = filenames;
        this.preset = preset;
        this.slowThresholdMs = slowThresholdMs;
        this.options = options;
    }

    public List<Path> getTempFiles() { return tempFiles; }
    public List<Path> getTempDirs() { return tempDirs; }
    public List<String> getFilenames() { return filenames; }
    public LogPreset getPreset() { return preset; }
    public int getSlowThresholdMs() { return slowThresholdMs; }
    public AnalysisOptions getOptions() { return options; }

    public void cleanupTempFiles() {
        for (Path f : tempFiles) {
            try { java.nio.file.Files.deleteIfExists(f); } catch (Exception ignored) {}
        }
        for (Path d : tempDirs) {
            try { java.nio.file.Files.deleteIfExists(d); } catch (Exception ignored) {}
        }
    }
}
