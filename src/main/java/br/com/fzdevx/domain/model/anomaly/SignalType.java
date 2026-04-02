package br.com.fzdevx.domain.model.anomaly;

public enum SignalType {
    SLOW_QUERY,
    GC_PAUSE,
    POOL_EXHAUSTION,
    POOL_LEAK,
    NPE,
    SQL_EXCEPTION,
    HTTP_ERROR,
    THREAD_REJECTION,
    OOM,
    DEADLOCK,
    ERROR_COUNT
}
