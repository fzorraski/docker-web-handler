package br.com.fzdevx.domain.model.anomaly;

import java.time.LocalDateTime;

public record Signal(
        SignalType signalType,
        Long numericValue,
        String rawMessage,
        LocalDateTime timestamp,
        String threadName,
        String loggerName
) {}
