package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.AnalysisOptions;
import br.com.fzdevx.application.port.DockerContainerPort;
import br.com.fzdevx.domain.model.LogAnalysis;
import br.com.fzdevx.domain.model.LogPreset;
import br.com.fzdevx.domain.shared.InputValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AnalyzeContainerLogsUseCase {

    private static final Logger LOG = Logger.getLogger(AnalyzeContainerLogsUseCase.class.getName());

    // Docker prepends ISO 8601 timestamps (e.g. "2026-05-20T10:40:00.949377221Z ") when withTimestamps(true)
    private static final Pattern DOCKER_TS_PREFIX = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}) ");
    // WildFly/Quarkus console handlers often log time-only (HH:mm:ss,SSS) without date
    private static final Pattern TIME_ONLY_START = Pattern.compile(
            "^\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}\\s");

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    AnalyzeLogFileUseCase analyzeLogFileUseCase;

    public LogAnalysis execute(String containerId, String containerName, int requestedLines,
                               String direction, LogPreset preset, int slowThresholdMs) throws IOException {
        return execute(containerId, containerName, requestedLines, direction, preset, slowThresholdMs,
                AnalyzeLogFileUseCase.Attribution.NONE);
    }

    public LogAnalysis execute(String containerId, String containerName, int requestedLines,
                               String direction, LogPreset preset, int slowThresholdMs,
                               AnalyzeLogFileUseCase.Attribution attribution) throws IOException {
        Optional<String> idError = InputValidator.validateContainerId(containerId);
        if (idError.isPresent()) {
            throw new ContainerLogException(idError.get());
        }

        boolean isHead = "head".equalsIgnoreCase(direction);
        int dockerTail = isHead ? Math.min(requestedLines * 5, 100_000) : requestedLines;

        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("log-analyzer-container-");
            String shortId = containerId.substring(0, Math.min(5, containerId.length()));
            String safeName = containerName != null && !containerName.isBlank()
                    ? containerName.replaceAll("[^a-zA-Z0-9._-]", "_") : "container";
            String filename = safeName + "-" + shortId + ".log";
            tempFile = tempDir.resolve(filename);

            AtomicReference<String> error = new AtomicReference<>();
            AtomicInteger lineCount = new AtomicInteger(0);
            int headLimit = isHead ? requestedLines : Integer.MAX_VALUE;

            try (var writer = Files.newBufferedWriter(tempFile)) {
                dockerContainerPort.collectLogs(containerId, dockerTail, event -> {
                    try {
                        if ("ERROR".equals(event.getType().name())) {
                            error.compareAndSet(null, event.getMessage());
                        } else if (event.getMessage() != null) {
                            if (!isHead || lineCount.incrementAndGet() <= headLimit) {
                                writer.write(stripDockerTimestamp(event.getMessage()));
                                writer.newLine();
                            }
                        }
                    } catch (IOException e) {
                        error.compareAndSet(null, "Failed to write log line: " + e.getMessage());
                    }
                });
            }

            if (error.get() != null) {
                throw new ContainerLogException(error.get());
            }

            return analyzeLogFileUseCase.analyze(
                    List.of(tempFile), List.of(filename), preset, slowThresholdMs,
                    AnalysisOptions.all(), attribution
            ).analysis();
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
            if (tempDir != null) {
                try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Strips the Docker-injected timestamp prefix and reconstructs a parseable log line.
     * Docker's withTimestamps(true) prepends "2026-05-20T10:40:00.949377221Z " to every line.
     * If the remaining line starts with a time-only timestamp (e.g. "07:40:00,949"),
     * the date from the Docker prefix is prepended to produce a full datetime that
     * matches standard preset patterns like "yyyy-MM-dd HH:mm:ss,SSS".
     *
     * <p>Note: the Docker date is UTC while the app timestamp is local time. Around midnight
     * UTC the prepended date may be off by one day. This is acceptable since log analysis
     * depends on relative line ordering, not absolute dates.</p>
     */
    static String stripDockerTimestamp(String line) {
        Matcher m = DOCKER_TS_PREFIX.matcher(line);
        if (!m.find()) return line;
        String date = m.group(1);
        String rest = line.substring(m.end());
        if (TIME_ONLY_START.matcher(rest).find()) {
            return date + " " + rest;
        }
        return rest;
    }

    public static class ContainerLogException extends RuntimeException {
        public ContainerLogException(String message) {
            super(message);
        }
    }
}
