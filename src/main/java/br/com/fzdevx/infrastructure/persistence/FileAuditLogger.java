package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends one JSON line per audit event to a dedicated file and mirrors it to
 * the application log. The actor is resolved lazily from the request-scoped
 * {@link CurrentUser}: the username under RBAC, "anonymous" in legacy password
 * mode, and "system" outside any request (e.g. scheduled executions).
 */
@ApplicationScoped
public class FileAuditLogger implements AuditLogger {

    private final ReentrantLock lock = new ReentrantLock();

    @Inject
    @ConfigProperty(name = "audit.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    @ConfigProperty(name = "audit.file", defaultValue = "data/audit.log")
    String file;

    @Inject
    CurrentUser currentUser;

    @Override
    public void log(String action, String target, String detail) {
        logAs(resolveActor(), action, target, detail);
    }

    @Override
    public void logAs(String actor, String action, String target, String detail) {
        if (!enabled) {
            return;
        }
        Log.infof("AUDIT user=%s action=%s target=%s%s",
                actor, action, target, detail == null ? "" : " detail=" + detail);
        append(toJsonLine(Instant.now(), actor, action, target, detail));
    }

    /**
     * Rewrites the audit file keeping only entries at or after the cutoff.
     * Lines whose timestamp cannot be parsed are kept (never silently lose data).
     *
     * @return the number of entries removed
     */
    public int removeEntriesOlderThan(Instant cutoff) {
        lock.lock();
        try {
            Path path = Paths.get(file);
            if (!Files.exists(path)) {
                return 0;
            }
            List<String> lines = Files.readAllLines(path);
            List<String> kept = lines.stream()
                    .filter(line -> {
                        Instant timestamp = parseTimestamp(line);
                        return timestamp == null || !timestamp.isBefore(cutoff);
                    })
                    .toList();
            int removed = lines.size() - kept.size();
            if (removed == 0) {
                return 0;
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + System.nanoTime());
            Files.write(tmp, kept);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return removed;
        } catch (IOException e) {
            Log.errorf(e, "Failed to clean up audit file %s.", file);
            return 0;
        } finally {
            lock.unlock();
        }
    }

    private static final String TIMESTAMP_PREFIX = "{\"timestamp\":\"";

    private static Instant parseTimestamp(String line) {
        if (!line.startsWith(TIMESTAMP_PREFIX)) {
            return null;
        }
        int end = line.indexOf('"', TIMESTAMP_PREFIX.length());
        if (end < 0) {
            return null;
        }
        try {
            return Instant.parse(line.substring(TIMESTAMP_PREFIX.length(), end));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveActor() {
        try {
            if (currentUser.isRbacActive() && currentUser.getUsername() != null) {
                return currentUser.getUsername();
            }
            return "anonymous";
        } catch (ContextNotActiveException e) {
            return "system";
        }
    }

    private void append(String line) {
        lock.lock();
        try {
            Path path = Paths.get(file);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.errorf(e, "Failed to write audit event to %s.", file);
        } finally {
            lock.unlock();
        }
    }

    private static String toJsonLine(Instant timestamp, String actor, String action,
                                     String target, String detail) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"timestamp\":\"").append(timestamp).append('"');
        sb.append(",\"user\":\"").append(escape(actor)).append('"');
        sb.append(",\"action\":\"").append(escape(action)).append('"');
        sb.append(",\"target\":\"").append(escape(target)).append('"');
        if (detail != null) {
            sb.append(",\"detail\":\"").append(escape(detail)).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
