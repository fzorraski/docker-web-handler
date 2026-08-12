package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends one JSON line per audit event to a dedicated file and mirrors it to
 * the application log. The actor is resolved lazily from the request-scoped
 * {@link CurrentUser}: the username under RBAC, "anonymous" in legacy password
 * mode, and "system" outside any request (e.g. scheduled executions).
 */
@ApplicationScoped
@jakarta.enterprise.inject.Typed(FileAuditLogger.class)
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
        logAs(br.com.fzdevx.infrastructure.config.AuditActor.resolve(currentUser), action, target, detail);
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
     * Lines whose timestamp cannot be parsed are kept (never silently lose
     * data). Streams line by line so memory stays bounded even for audit
     * files that grew for months with retention disabled.
     *
     * @return the number of entries removed
     */
    @Override
    public int removeEntriesOlderThan(Instant cutoff) {
        lock.lock();
        try {
            Path path = Paths.get(file);
            if (!Files.exists(path)) {
                return 0;
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + System.nanoTime());
            int removed = 0;
            try (BufferedReader reader = Files.newBufferedReader(path);
                 BufferedWriter writer = Files.newBufferedWriter(tmp)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Instant timestamp = parseTimestamp(line);
                    if (timestamp != null && timestamp.isBefore(cutoff)) {
                        removed++;
                    } else {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            if (removed == 0) {
                Files.deleteIfExists(tmp);
                return 0;
            }
            AtomicFileWriter.move(tmp, path);
            return removed;
        } catch (IOException e) {
            Log.errorf(e, "Failed to clean up audit file %s.", file);
            return 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public br.com.fzdevx.application.dto.AuditSearchResult search(
            br.com.fzdevx.application.dto.AuditSearchCriteria criteria) {
        java.util.List<br.com.fzdevx.domain.model.AuditEntry> matches = new java.util.ArrayList<>();
        for (br.com.fzdevx.domain.model.AuditEntry entry : readAllEntries()) {
            if (matches(entry, criteria)) {
                matches.add(entry);
            }
        }
        // newest first (file is append-ordered)
        java.util.Collections.reverse(matches);
        int fromIndex = Math.min(criteria.page() * criteria.size(), matches.size());
        int toIndex = Math.min(fromIndex + criteria.size(), matches.size());
        return new br.com.fzdevx.application.dto.AuditSearchResult(
                java.util.List.copyOf(matches.subList(fromIndex, toIndex)), matches.size());
    }

    @Override
    public java.util.List<String> distinctActions() {
        java.util.TreeSet<String> actions = new java.util.TreeSet<>();
        for (br.com.fzdevx.domain.model.AuditEntry entry : readAllEntries()) {
            if (entry.action() != null) {
                actions.add(entry.action());
            }
        }
        return java.util.List.copyOf(actions);
    }

    private static boolean matches(br.com.fzdevx.domain.model.AuditEntry entry,
                                   br.com.fzdevx.application.dto.AuditSearchCriteria criteria) {
        if (criteria.actor() != null && !criteria.actor().isBlank()
                && (entry.actor() == null || !entry.actor().equalsIgnoreCase(criteria.actor().trim()))) {
            return false;
        }
        if (criteria.action() != null && !criteria.action().isBlank()
                && !criteria.action().trim().equals(entry.action())) {
            return false;
        }
        if (criteria.text() != null && !criteria.text().isBlank()) {
            String needle = criteria.text().trim().toLowerCase();
            boolean inTarget = entry.target() != null && entry.target().toLowerCase().contains(needle);
            boolean inDetail = entry.detail() != null && entry.detail().toLowerCase().contains(needle);
            if (!inTarget && !inDetail) {
                return false;
            }
        }
        if (criteria.from() != null && (entry.timestamp() == null || entry.timestamp().isBefore(criteria.from()))) {
            return false;
        }
        if (criteria.to() != null && (entry.timestamp() == null || entry.timestamp().isAfter(criteria.to()))) {
            return false;
        }
        return true;
    }

    private java.util.List<br.com.fzdevx.domain.model.AuditEntry> readAllEntries() {
        lock.lock();
        try {
            Path path = Paths.get(file);
            if (!Files.exists(path)) {
                return java.util.List.of();
            }
            java.util.List<br.com.fzdevx.domain.model.AuditEntry> entries = new java.util.ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    br.com.fzdevx.domain.model.AuditEntry entry = parseLine(line);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            return entries;
        } catch (IOException e) {
            Log.errorf(e, "Failed to read audit file %s.", file);
            return java.util.List.of();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private static br.com.fzdevx.domain.model.AuditEntry parseLine(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            java.util.Map<String, Object> parsed = br.com.fzdevx.infrastructure.persistence.jdbc.JdbcSupport.JSONB
                    .fromJson(line, java.util.Map.class);
            Object timestamp = parsed.get("timestamp");
            return new br.com.fzdevx.domain.model.AuditEntry(
                    timestamp == null ? null : Instant.parse(String.valueOf(timestamp)),
                    stringOrNull(parsed.get("user")),
                    stringOrNull(parsed.get("action")),
                    stringOrNull(parsed.get("target")),
                    stringOrNull(parsed.get("detail")));
        } catch (Exception e) {
            // unparseable lines are skipped for browsing (retention keeps them)
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
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
