package br.com.fzdevx.application.usecase;

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

@ApplicationScoped
public class AnalyzeContainerLogsUseCase {

    private static final Logger LOG = Logger.getLogger(AnalyzeContainerLogsUseCase.class.getName());

    @Inject
    DockerContainerPort dockerContainerPort;

    @Inject
    AnalyzeLogFileUseCase analyzeLogFileUseCase;

    public LogAnalysis execute(String containerId, String containerName, int requestedLines,
                               String direction, LogPreset preset, int slowThresholdMs) throws IOException {
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
                                writer.write(event.getMessage());
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
                    List.of(tempFile), List.of(filename), preset, slowThresholdMs
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

    public static class ContainerLogException extends RuntimeException {
        public ContainerLogException(String message) {
            super(message);
        }
    }
}
