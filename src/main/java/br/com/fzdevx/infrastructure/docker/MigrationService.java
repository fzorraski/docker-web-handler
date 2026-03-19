package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import br.com.fzdevx.infrastructure.persistence.JsonFileMigrationRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Bind;
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

@ApplicationScoped
public class MigrationService {

    private static final String EPHEMERAL_LABEL = "docker-web-handler.ephemeral";
    private static final String STEP_NAME = "Running Migration";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Inject
    JsonFileMigrationRepository migrationRepository;

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

    public void recordMigration(String databaseName, String repository, String mode,
                                MigrationResult result) {
        DatabaseMigrationRecord record = new DatabaseMigrationRecord(
                databaseName, repository, mode,
                result != null ? result.sourceVersion() : null,
                result != null ? result.targetVersion() : null,
                result != null ? result.versionsIncluded() : null,
                result != null ? result.totalStatements() : null);
        migrationRepository.save(record);
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
            eventSink.accept(ContainerEvent.error(STEP_NAME, "Migration API returned no SQL statements."));
            return null;
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
        String url = cleaned
                .replace("{sourceVersion}", sourceVersion)
                .replace("{targetVersion}", targetVersion);

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

        // Optional: sourceVersion
        String sourceVersion = obj.containsKey("sourceVersion") && !obj.isNull("sourceVersion")
                ? obj.getString("sourceVersion") : null;

        // Optional: targetVersion
        String targetVersion = obj.containsKey("targetVersion") && !obj.isNull("targetVersion")
                ? obj.getString("targetVersion") : null;

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
                    versionsIncluded.add(js.getString());
                }
            }
        }

        return new MigrationResult(sql, sourceVersion, targetVersion, totalStatements, versionsIncluded);
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
            migrationSql = sql;
        } else {
            migrationResult = fetchMigrationSql(repository, sourceVersion, targetVersion, eventSink);
            if (migrationResult == null) {
                return false;
            }
            migrationSql = migrationResult.sql();
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
        Path tempFile = null;
        String containerId = null;
        try {
            tempFile = Files.createTempFile("migration-", ".sql");
            Files.writeString(tempFile, sql);

            eventSink.accept(ContainerEvent.info(STEP_NAME, "Creating ephemeral migration container..."));

            CreateContainerResponse container = dockerClient.createContainerCmd(pgImage)
                    .withCmd("tail", "-f", "/dev/null")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withNetworkMode("host")
                            .withBinds(Bind.parse(tempFile.toAbsolutePath() + ":/migration/migration.sql:ro")))
                    .withLabels(Map.of(EPHEMERAL_LABEL, "true"))
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

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
