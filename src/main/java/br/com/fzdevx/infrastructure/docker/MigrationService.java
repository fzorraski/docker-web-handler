package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class MigrationService {

    private static final String EPHEMERAL_LABEL = "docker-web-handler.ephemeral";
    private static final String STEP_NAME = "Running Migration";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Inject
    br.com.fzdevx.application.port.MigrationRecordRepository migrationRepository;

    @Inject
    br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;

    @Inject
    ResourceCounterService resourceCounterService;

    @Inject
    Config config;

    @Inject
    DockerClient dockerClient;

    @Inject
    @ConfigProperty(name = "database.migration.enabled", defaultValue = "false")
    boolean featureEnabled;

    @Inject
    @ConfigProperty(name = "database.migration.api-url")
    Optional<String> globalApiUrl;

    /**
     * Holds the parsed migration API response: SQL text + optional metadata.
     */
    public record MigrationResult(
            String sql,
            String sourceVersion,
            String targetVersion,
            Integer totalStatements,
            List<String> versionsIncluded
    ) {}

    public boolean isEnabled() {
        return featureEnabled;
    }

    public String getApiUrl(String repository) {
        return config.getOptionalValue("repository.migration-api-url." + repository, String.class)
                .filter(v -> !v.isBlank())
                .or(() -> globalApiUrl.filter(v -> !v.isBlank()))
                .orElse(null);
    }

    private record TagComponents(String branch, String version) {}

    private Optional<String> getTagVersionPattern(String repository) {
        return config.getOptionalValue("repository.tag-version-pattern." + repository, String.class)
                .filter(v -> !v.isBlank());
    }

    private final Map<String, Pattern> tagPatternCache = new java.util.concurrent.ConcurrentHashMap<>();

    private TagComponents parseTag(String tag, String repository) {
        Optional<String> patternStr = getTagVersionPattern(repository);
        if (patternStr.isEmpty()) {
            return null;
        }
        try {
            Pattern pattern = tagPatternCache.computeIfAbsent(patternStr.get(), Pattern::compile);
            Matcher matcher = pattern.matcher(tag);
            if (matcher.matches()) {
                String branch = null;
                String version = null;
                try { branch = matcher.group("branch"); } catch (IllegalArgumentException ignored) {}
                try { version = matcher.group("version"); } catch (IllegalArgumentException ignored) {}
                if (branch != null && version != null) {
                    return new TagComponents(branch, version);
                }
                Log.warnf("Tag pattern for '%s' matched tag '%s' but missing required named groups (branch=%s, version=%s)",
                        repository, tag, branch, version);
            }
        } catch (PatternSyntaxException e) {
            Log.errorf("Invalid tag-version-pattern for repository '%s': %s", repository, e.getMessage());
        }
        return null;
    }

    public void recordMigration(String databaseName, String repository, String mode,
                                MigrationResult result) {
        DatabaseMigrationRecord record = new DatabaseMigrationRecord(
                databaseName, repository, mode,
                result != null ? result.sourceVersion() : null,
                result != null ? result.targetVersion() : null,
                result != null ? result.versionsIncluded() : null,
                result != null ? result.totalStatements() : null);
        // both callers (container create SSE, standalone migration SSE) run in
        // request scope, so this is the real user - "system" only for workers
        record.setMigratedBy(actorResolver.usernameOrSystem());
        migrationRepository.save(record);
        resourceCounterService.increment(ResourceCounterService.MIGRATIONS_EXECUTED);
    }

    public List<DatabaseMigrationRecord> getMigratedDatabases() {
        return migrationRepository.findAll();
    }

    public MigrationResult previewMigration(String repository, String sourceVersion, String targetVersion) {
        return callMigrationApi(repository, sourceVersion, targetVersion);
    }

    public boolean isApiAvailable(String repository) {
        String url = getApiUrl(repository);
        return url != null && !url.isBlank();
    }

    public MigrationResult fetchMigrationSql(String repository, String sourceVersion, String targetVersion,
                                              Consumer<ContainerEvent> eventSink) {
        eventSink.accept(ContainerEvent.info(STEP_NAME, "Fetching migration SQL from API..."));

        MigrationResult result = callMigrationApi(repository, sourceVersion, targetVersion);
        if (result == null) {
            eventSink.accept(ContainerEvent.error(STEP_NAME,
                    "Failed to fetch migration SQL from API."));
            return null;
        }
        if (result.sql().isBlank()) {
            StringBuilder noOp = new StringBuilder("No migration needed");
            if (result.sourceVersion() != null && result.targetVersion() != null) {
                noOp.append(" (").append(result.sourceVersion())
                        .append(" → ").append(result.targetVersion()).append(")");
            }
            noOp.append(" — API returned no SQL statements.");
            eventSink.accept(ContainerEvent.info(STEP_NAME, noOp.toString()));
            return result;
        }

        // Emit metadata as SSE info events
        StringBuilder summary = new StringBuilder("Migration SQL fetched");
        if (result.sourceVersion() != null && result.targetVersion() != null) {
            summary.append(" (").append(result.sourceVersion())
                    .append(" \u2192 ").append(result.targetVersion()).append(")");
        }
        if (result.totalStatements() != null) {
            summary.append(" \u2014 ").append(result.totalStatements()).append(" statement(s)");
        } else {
            long counted = result.sql().lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("--")).count();
            summary.append(" \u2014 ").append(counted).append(" statement(s)");
        }
        summary.append(", ").append(result.sql().length()).append(" characters.");
        eventSink.accept(ContainerEvent.info(STEP_NAME, summary.toString()));

        if (result.versionsIncluded() != null && !result.versionsIncluded().isEmpty()) {
            eventSink.accept(ContainerEvent.info(STEP_NAME,
                    "Versions included: " + String.join(", ", result.versionsIncluded())));
        }

        return result;
    }

    private MigrationResult callMigrationApi(String repository, String sourceVersion, String targetVersion) {
        String urlTemplate = getApiUrl(repository);
        if (urlTemplate == null || urlTemplate.isBlank()) {
            return null;
        }

        String cleaned = urlTemplate
                .replace("\u201C", "").replace("\u201D", "")
                .replace(String.valueOf((char) 0x22), "").replace("\u201E", "")
                .trim();

        String effectiveTargetVersion = targetVersion;
        String branch = null;

        if (cleaned.contains("{branch}")) {
            TagComponents parsed = parseTag(targetVersion, repository);
            if (parsed != null) {
                branch = parsed.branch();
                effectiveTargetVersion = parsed.version();
            }
        }

        String url = cleaned
                .replace("{sourceVersion}", sourceVersion)
                .replace("{targetVersion}", effectiveTargetVersion);

        if (branch != null) {
            url = url.replace("{branch}", branch);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                Log.warnf("Migration API returned status %d for %s", response.statusCode(), url);
                return null;
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                return null;
            }

            return parseResponseBody(body);
        } catch (Exception e) {
            Log.errorf("Failed to call migration API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the API response body. Supports three formats:
     * <ol>
     *   <li>JSON object: {@code {"statements":["sql1","sql2"], "sourceVersion":"1.0", "targetVersion":"2.0",
     *       "totalStatements":2, "versionsIncluded":["1.1","2.0"]}}</li>
     *   <li>JSON array of strings: {@code ["sql1","sql2"]}</li>
     *   <li>Plain SQL text</li>
     * </ol>
     */
    MigrationResult parseResponseBody(String body) {
        String trimmed = body.trim();

        // Try JSON parsing
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try (JsonReader reader = Json.createReader(new StringReader(trimmed))) {
                JsonStructure structure = reader.read();

                if (structure instanceof JsonObject obj) {
                    return parseJsonObject(obj);
                } else if (structure instanceof JsonArray arr) {
                    String sql = joinStatements(arr);
                    return new MigrationResult(sql, null, null, null, null);
                }
            } catch (Exception e) {
                Log.warnf("Failed to parse migration response as JSON, treating as plain SQL: %s", e.getMessage());
            }
        }

        // Plain SQL fallback
        return new MigrationResult(trimmed, null, null, null, null);
    }

    private static final int MAX_VERSION_LENGTH = 128;
    private static final int MAX_VERSIONS_INCLUDED = 256;

    private MigrationResult parseJsonObject(JsonObject obj) {
        // Required: statements
        String sql;
        if (obj.containsKey("statements")) {
            sql = joinStatements(obj.getJsonArray("statements"));
        } else {
            // Fallback: treat entire object as unexpected, return empty
            Log.warn("Migration API response object has no 'statements' field.");
            return new MigrationResult("", null, null, null, null);
        }

        // Optional: sourceVersion — sanitized to defend against log/SSE injection
        // and oversized payloads from a misbehaving or compromised migration API.
        String sourceVersion = obj.containsKey("sourceVersion") && !obj.isNull("sourceVersion")
                ? sanitizeVersion(obj.getString("sourceVersion")) : null;

        // Optional: targetVersion
        String targetVersion = obj.containsKey("targetVersion") && !obj.isNull("targetVersion")
                ? sanitizeVersion(obj.getString("targetVersion")) : null;

        // Optional: totalStatements
        Integer totalStatements = obj.containsKey("totalStatements") && !obj.isNull("totalStatements")
                ? obj.getInt("totalStatements") : null;

        // Optional: versionsIncluded
        List<String> versionsIncluded = null;
        if (obj.containsKey("versionsIncluded") && !obj.isNull("versionsIncluded")) {
            JsonArray arr = obj.getJsonArray("versionsIncluded");
            versionsIncluded = new ArrayList<>();
            for (JsonValue v : arr) {
                if (v instanceof JsonString js) {
                    if (versionsIncluded.size() >= MAX_VERSIONS_INCLUDED) break;
                    String sanitized = sanitizeVersion(js.getString());
                    if (sanitized != null) {
                        versionsIncluded.add(sanitized);
                    }
                }
            }
        }

        return new MigrationResult(sql, sourceVersion, targetVersion, totalStatements, versionsIncluded);
    }

    private static String sanitizeVersion(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_VERSION_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_VERSION_LENGTH; i++) {
            char c = raw.charAt(i);
            // Strip ISO control chars (CR/LF/TAB/ANSI escape, etc.) — these are the
            // primary log-injection / SSE-injection vectors. Allow normal printable
            // chars; non-ASCII is left intact for unicode version tags.
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String joinStatements(JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (JsonValue v : arr) {
            if (v instanceof JsonString js) {
                String statement = js.getString().trim();
                if (statement.isEmpty()) continue;
                if (!sb.isEmpty()) sb.append('\n');
                if (!statement.startsWith("--") && !statement.endsWith(";")) {
                    sb.append(statement).append(';');
                } else {
                    sb.append(statement);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Orchestrates a full migration flow: fetch SQL (if API mode) or use provided SQL (if MANUAL),
     * execute the migration, and record the result on success.
     *
     * @return true on success, false on failure
     */
    public boolean orchestrateMigration(String mode, String sql, String sourceVersion, String targetVersion,
                                         String repository, String targetDatabase, String pgImage,
                                         DatabasePort.PgConnectionInfo pgInfo,
                                         Consumer<ContainerEvent> eventSink, AtomicBoolean cancelled) {
        String migrationSql;
        MigrationResult migrationResult = null;
        if ("MANUAL".equals(mode)) {
            // MANUAL mode with blank SQL is a caller error — fail-closed so we don't
            // silently record a no-op as a successful migration.
            if (sql == null || sql.isBlank()) {
                eventSink.accept(ContainerEvent.error(STEP_NAME,
                        "Migration SQL is required for MANUAL mode."));
                return false;
            }
            migrationSql = sql;
        } else {
            migrationResult = fetchMigrationSql(repository, sourceVersion, targetVersion, eventSink);
            if (migrationResult == null) {
                return false;
            }
            migrationSql = migrationResult.sql();
        }

        // API no-op: the migration API correctly reported there is nothing to
        // migrate between source and target. Treat as success but do not record —
        // recording would overwrite any prior real migration record for the same
        // (database, repository) and pollute the executed-migrations counter.
        if (migrationSql == null || migrationSql.isBlank()) {
            return true;
        }

        boolean ok = executeMigration(migrationSql, pgInfo, targetDatabase, pgImage, eventSink, cancelled);
        if (!ok) {
            return false;
        }

        recordMigration(targetDatabase, repository, mode, migrationResult);
        return true;
    }

    public boolean executeMigration(String sql, DatabasePort.PgConnectionInfo pgInfo,
                                     String targetDb, String pgImage,
                                     Consumer<ContainerEvent> eventSink,
                                     AtomicBoolean cancelled) {
        if (sql == null || sql.isBlank()) {
            eventSink.accept(ContainerEvent.error(STEP_NAME, "No migration SQL to execute."));
            return false;
        }

        Log.debugf("Migration SQL length: %d, first 200 chars: %s",
                sql.length(), sql.substring(0, Math.min(200, sql.length())));
        eventSink.accept(ContainerEvent.info(STEP_NAME,
                "Executing migration SQL (" + sql.length() + " chars, pgImage=" + pgImage + ")..."));

        boolean useDocker = !"none".equalsIgnoreCase(pgImage);

        if (useDocker) {
            return executeViaDocker(sql, pgInfo, targetDb, pgImage, eventSink, cancelled);
        } else {
            return executeLocally(sql, pgInfo, targetDb, eventSink, cancelled);
        }
    }

    private boolean executeViaDocker(String sql, DatabasePort.PgConnectionInfo pgInfo,
                                      String targetDb, String pgImage,
                                      Consumer<ContainerEvent> eventSink,
                                      AtomicBoolean cancelled) {
        Path tempDir = null;
        Path tempFile = null;
        String containerId = null;
        try {
            tempDir = Files.createTempDirectory("migration-");
            tempFile = tempDir.resolve("migration.sql");
            Files.writeString(tempFile, sql);

            eventSink.accept(ContainerEvent.info(STEP_NAME, "Creating ephemeral migration container..."));

            CreateContainerResponse container = dockerClient.createContainerCmd(pgImage)
                    .withCmd("tail", "-f", "/dev/null")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode("host"))
                    .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            ExecCreateCmdResponse mkdirExec = dockerClient.execCreateCmd(containerId)
                    .withCmd("mkdir", "-p", "/migration")
                    .exec();
            dockerClient.execStartCmd(mkdirExec.getId()).exec(new ExecStartResultCallback())
                    .awaitCompletion();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(tempFile.toAbsolutePath().toString())
                    .withRemotePath("/migration/")
                    .exec();

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error(STEP_NAME, "Migration cancelled."));
                return false;
            }

            ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                    .withCmd("psql",
                            "-h", pgInfo.host(),
                            "-p", String.valueOf(pgInfo.port()),
                            "-U", pgInfo.user(),
                            "-d", targetDb,
                            "-f", "/migration/migration.sql")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withEnv(List.of("PGPASSWORD=" + pgInfo.password()))
                    .exec();

            PipedInputStream stdoutPipe = new PipedInputStream();
            PipedOutputStream stdoutSink = new PipedOutputStream(stdoutPipe);

            ExecStartResultCallback callback = dockerClient.execStartCmd(exec.getId())
                    .exec(new ExecStartResultCallback(stdoutSink, stdoutSink));

            Thread outputReader = Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutPipe))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        eventSink.accept(ContainerEvent.progress(STEP_NAME, line, -1));
                    }
                } catch (Exception e) {
                    Log.warnf("Error reading migration output: %s", e.getMessage());
                }
            });

            callback.awaitCompletion();
            stdoutSink.close();
            outputReader.join();

            InspectExecResponse inspectResponse = dockerClient.inspectExecCmd(exec.getId()).exec();
            Long exitCodeLong = inspectResponse.getExitCodeLong();
            int exitCode = exitCodeLong != null ? exitCodeLong.intValue() : -1;

            if (exitCode != 0) {
                eventSink.accept(ContainerEvent.error(STEP_NAME,
                        "Migration failed with exit code " + exitCode + "."));
                return false;
            }

            eventSink.accept(ContainerEvent.info(STEP_NAME, "Migration completed successfully."));
            return true;

        } catch (Exception e) {
            Log.errorf("Migration execution failed: %s", e.getMessage());
            eventSink.accept(ContainerEvent.error(STEP_NAME,
                    "Migration execution failed: " + e.getMessage()));
            return false;
        } finally {
            if (containerId != null) {
                try {
                    dockerClient.stopContainerCmd(containerId).withTimeout(2).exec();
                } catch (Exception ignored) {}
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    eventSink.accept(ContainerEvent.info(STEP_NAME,
                            "Ephemeral migration container removed."));
                } catch (Exception e) {
                    Log.warnf("Failed to remove ephemeral migration container: %s", e.getMessage());
                }
            }
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
            if (tempDir != null) {
                try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
            }
        }
    }

    private boolean executeLocally(String sql, DatabasePort.PgConnectionInfo pgInfo,
                                    String targetDb,
                                    Consumer<ContainerEvent> eventSink,
                                    AtomicBoolean cancelled) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("migration-", ".sql");
            Files.writeString(tempFile, sql);

            if (cancelled.get()) {
                eventSink.accept(ContainerEvent.error(STEP_NAME, "Migration cancelled."));
                return false;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "psql",
                    "-h", pgInfo.host(),
                    "-p", String.valueOf(pgInfo.port()),
                    "-U", pgInfo.user(),
                    "-d", targetDb,
                    "-f", tempFile.toAbsolutePath().toString());
            pb.environment().put("PGPASSWORD", pgInfo.password());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    eventSink.accept(ContainerEvent.progress(STEP_NAME, line, -1));
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                eventSink.accept(ContainerEvent.error(STEP_NAME,
                        "Migration failed with exit code " + exitCode + "."));
                return false;
            }

            eventSink.accept(ContainerEvent.info(STEP_NAME, "Migration completed successfully."));
            return true;

        } catch (Exception e) {
            eventSink.accept(ContainerEvent.error(STEP_NAME,
                    "Migration execution error: " + e.getMessage()));
            return false;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }
}
