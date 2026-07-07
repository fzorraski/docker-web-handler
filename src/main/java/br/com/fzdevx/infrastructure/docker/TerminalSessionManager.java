package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DockerTerminalPort;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.websocket.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class TerminalSessionManager {

    @Inject
    br.com.fzdevx.infrastructure.config.RuntimeSettingsService runtimeSettings;

    private final ConcurrentHashMap<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> idleCheckFuture;

    void onShutdown(@Observes ShutdownEvent event) {
        for (TerminalSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (idleCheckFuture != null) idleCheckFuture.cancel(false);
        scheduler.shutdownNow();
        Log.info("Terminal session manager shut down.");
    }

    /**
     * Atomically checks the session limit and registers a new session.
     * Returns empty if the limit has been reached.
     */
    public synchronized Optional<TerminalSession> tryRegisterSession(
            String sessionId, Session wsSession,
            DockerTerminalPort.ExecSession execSession,
            String containerId, String execId) {
        if (sessions.size() >= runtimeSettings.getTerminalMaxSessions()) {
            return Optional.empty();
        }
        TerminalSession ts = new TerminalSession(sessionId, wsSession, execSession, containerId, execId);
        sessions.put(sessionId, ts);
        ensureIdleCheckRunning();
        Log.infof("Terminal session registered: %s (container: %s, active: %d)",
                sessionId, containerId, sessions.size());
        return Optional.of(ts);
    }

    public void removeSession(String sessionId) {
        TerminalSession ts = sessions.remove(sessionId);
        if (ts != null) {
            ts.close();
            Log.infof("Terminal session removed: %s (active: %d)", sessionId, sessions.size());
        }
    }

    public TerminalSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    private void ensureIdleCheckRunning() {
        if (idleCheckFuture == null || idleCheckFuture.isDone()) {
            idleCheckFuture = scheduler.scheduleAtFixedRate(this::checkIdleSessions,
                    1, 1, TimeUnit.MINUTES);
        }
    }

    private void checkIdleSessions() {
        long now = System.currentTimeMillis();
        int idleTimeoutMinutes = runtimeSettings.getTerminalIdleTimeoutMinutes();
        long timeoutMs = idleTimeoutMinutes * 60_000L;
        List<String> expired = new ArrayList<>();
        for (var entry : sessions.entrySet()) {
            if (now - entry.getValue().getLastActivityAt() > timeoutMs) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            TerminalSession ts = sessions.get(key);
            if (ts != null) {
                Log.infof("Terminal session '%s' idle for >%d minutes, closing.", key, idleTimeoutMinutes);
                removeSession(key);
                try {
                    if (ts.getWsSession().isOpen()) {
                        ts.getWsSession().close();
                    }
                } catch (Exception e) {
                    Log.warnf("Failed to close idle terminal WebSocket: %s", e.getMessage());
                }
            }
        }
    }

    public static class TerminalSession {
        private final String sessionId;
        private final Session wsSession;
        private final DockerTerminalPort.ExecSession execSession;
        private final String containerId;
        private final String execId;
        private volatile long lastActivityAt;

        TerminalSession(String sessionId, Session wsSession,
                        DockerTerminalPort.ExecSession execSession,
                        String containerId, String execId) {
            this.sessionId = sessionId;
            this.wsSession = wsSession;
            this.execSession = execSession;
            this.containerId = containerId;
            this.execId = execId;
            this.lastActivityAt = System.currentTimeMillis();
        }

        public String getSessionId() { return sessionId; }
        public Session getWsSession() { return wsSession; }
        public DockerTerminalPort.ExecSession getExecSession() { return execSession; }
        public String getContainerId() { return containerId; }
        public String getExecId() { return execId; }
        public long getLastActivityAt() { return lastActivityAt; }
        public void touch() { this.lastActivityAt = System.currentTimeMillis(); }

        void close() {
            try { execSession.close(); } catch (Exception e) {
                Log.warnf("Error closing terminal exec session '%s': %s", execId, e.getMessage());
            }
        }
    }
}
