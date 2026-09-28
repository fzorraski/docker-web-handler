package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.exception.InvalidInputException;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseMigrationRecord;
import br.com.fzdevx.infrastructure.persistence.JsonFileMigrationRepository;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import com.github.dockerjava.api.DockerClient;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrationServiceTest {

    @Mock JsonFileMigrationRepository migrationRepository;
    @Mock br.com.fzdevx.infrastructure.config.ActorResolver actorResolver;
    @Mock ResourceCounterService resourceCounterService;
    @Mock Config config;
    @Mock DockerClient dockerClient;

    MigrationService service;

    private HttpServer httpServer;

    private final List<ContainerEvent> events = new ArrayList<>();
    private final Consumer<ContainerEvent> eventSink = events::add;

    @BeforeEach
    void setUp() {
        events.clear();
        service = new MigrationService();
        service.migrationRepository = migrationRepository;
        service.actorResolver = actorResolver;
        service.resourceCounterService = resourceCounterService;
        service.config = config;
        service.dockerClient = dockerClient;
        service.featureEnabled = true;
        service.globalApiUrl = Optional.empty();
        service.globalDefaultBranch = Optional.empty();

        when(config.getOptionalValue(anyString(), eq(String.class))).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    private void startApiServer(String responseBody) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/migrate", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();
        service.globalApiUrl = Optional.of(
                "http://127.0.0.1:" + port + "/migrate?from={sourceVersion}&to={targetVersion}");
    }

    private final java.util.concurrent.atomic.AtomicReference<String> requestedPath =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Serves /migrate/** and records the requested path, for {branch} URL templates. */
    private void startBranchApiServer() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/migrate", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = "{\"statements\":[\"SELECT 1;\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();
        service.globalApiUrl = Optional.of(
                "http://127.0.0.1:" + port + "/migrate/{branch}/{sourceVersion}/{targetVersion}");
    }

    private static final String TAG_PATTERN = "^(?<branch>.+)-(?<version>\\d+\\.\\d+\\.\\d+.*)$";

    @Test
    void callMigrationApi_branchPlaceholder_targetMatchesPattern_splitsBranchAndVersion() throws Exception {
        startBranchApiServer();
        when(config.getOptionalValue("repository.tag-version-pattern.schulz", String.class))
                .thenReturn(Optional.of(TAG_PATTERN));

        MigrationService.MigrationResult result = service.previewMigration("schulz", "20.0.0", "main-20.100.0");

        assertNotNull(result);
        assertEquals("/migrate/main/20.0.0/20.100.0", requestedPath.get());
    }

    @Test
    void callMigrationApi_branchPlaceholder_plainVersion_usesPerRepositoryDefaultBranch() throws Exception {
        startBranchApiServer();
        when(config.getOptionalValue("repository.tag-version-pattern.schulz", String.class))
                .thenReturn(Optional.of(TAG_PATTERN));
        when(config.getOptionalValue("repository.migration-default-branch.schulz", String.class))
                .thenReturn(Optional.of("release"));

        MigrationService.MigrationResult result = service.previewMigration("schulz", "20.0.0", "20.100.0");

        assertNotNull(result);
        assertEquals("/migrate/release/20.0.0/20.100.0", requestedPath.get());
    }

    @Test
    void callMigrationApi_branchPlaceholder_plainVersion_fallsBackToGlobalDefaultBranch() throws Exception {
        startBranchApiServer();
        when(config.getOptionalValue("repository.tag-version-pattern.schulz", String.class))
                .thenReturn(Optional.of(TAG_PATTERN));
        service.globalDefaultBranch = Optional.of("main");

        service.previewMigration("schulz", "20.0.0", "20.100.0");

        assertEquals("/migrate/main/20.0.0/20.100.0", requestedPath.get());
    }

    @Test
    void callMigrationApi_branchPlaceholder_plainVersion_noDefaultBranch_throwsActionableError() throws Exception {
        startBranchApiServer();
        when(config.getOptionalValue("repository.tag-version-pattern.schulz", String.class))
                .thenReturn(Optional.of(TAG_PATTERN));

        InvalidInputException ex = assertThrows(InvalidInputException.class,
                () -> service.previewMigration("schulz", "20.0.0", "20.100.0"));

        assertTrue(ex.getMessage().contains("20.100.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("repository.migration-default-branch.schulz"), ex.getMessage());
        assertNull(requestedPath.get(), "the API must not be called with a broken URL");
    }

    @Test
    void callMigrationApi_branchPlaceholder_noPatternAndNoDefault_throwsConfigError() throws Exception {
        startBranchApiServer();

        InvalidInputException ex = assertThrows(InvalidInputException.class,
                () -> service.previewMigration("schulz", "20.0.0", "20.100.0"));

        assertTrue(ex.getMessage().contains("repository.tag-version-pattern.schulz"), ex.getMessage());
        assertNull(requestedPath.get());
    }

    @Test
    void fetchMigrationSql_branchUnresolvable_emitsTheActionableMessageAsError() throws Exception {
        startBranchApiServer();
        when(config.getOptionalValue("repository.tag-version-pattern.schulz", String.class))
                .thenReturn(Optional.of(TAG_PATTERN));

        MigrationService.MigrationResult result =
                service.fetchMigrationSql("schulz", "20.0.0", "20.100.0", eventSink);

        assertNull(result);
        assertTrue(events.stream().anyMatch(e ->
                        e.getType() == ContainerEvent.EventType.ERROR
                                && e.getMessage().contains("does not match the tag pattern")),
                "the SSE error must explain why the branch could not be resolved");
    }

    @Test
    void parseResponseBody_emptyStatementsArray_returnsBlankSqlWithMetadata() {
        MigrationService.MigrationResult result = service.parseResponseBody(
                "{\"statements\":[],\"sourceVersion\":\"1.0\",\"targetVersion\":\"2.0\",\"totalStatements\":0}");

        assertNotNull(result);
        assertTrue(result.sql().isBlank(), "empty statements array should produce blank SQL");
        assertEquals("1.0", result.sourceVersion());
        assertEquals("2.0", result.targetVersion());
        assertEquals(0, result.totalStatements());
    }

    @Test
    void fetchMigrationSql_apiReturnsNoStatements_emitsInfoAndReturnsResult() throws Exception {
        startApiServer("{\"statements\":[],\"sourceVersion\":\"20.91.1\",\"targetVersion\":\"20.91.3\"}");

        MigrationService.MigrationResult result =
                service.fetchMigrationSql("schulz", "20.91.1", "20.91.3", eventSink);

        assertNotNull(result, "empty migration response must not be treated as a failure");
        assertTrue(result.sql().isBlank());

        assertTrue(events.stream().noneMatch(e -> e.getType() == ContainerEvent.EventType.ERROR),
                "no ERROR event should be emitted for an empty migration");
        assertTrue(events.stream().anyMatch(e ->
                        e.getType() == ContainerEvent.EventType.INFO
                                && e.getMessage().contains("No migration needed")),
                "an INFO event explaining the no-op should be emitted");
    }

    @Test
    void orchestrateMigration_apiReturnsNoStatements_succeedsButDoesNotRecordOrOverwriteHistory() throws Exception {
        startApiServer("{\"statements\":[],\"sourceVersion\":\"20.91.1\",\"targetVersion\":\"20.91.3\"}");

        boolean ok = service.orchestrateMigration(
                "API", null, "20.91.1", "20.91.3",
                "schulz", "schulz_compressores_prod", "postgres:16",
                new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pw"),
                eventSink, new AtomicBoolean(false));

        assertTrue(ok, "no-op migration should be reported as success so container creation can continue");
        // Critical: must NOT call save() — that would overwrite any prior real
        // migration record for the same (database, repository) with a no-op row.
        verify(migrationRepository, never()).save(any(DatabaseMigrationRecord.class));
        verify(resourceCounterService, never()).increment(ResourceCounterService.MIGRATIONS_EXECUTED);
        verifyNoInteractions(dockerClient);
    }

    @Test
    void orchestrateMigration_manualModeWithBlankSql_failsClosedAndDoesNotRecord() {
        boolean ok = service.orchestrateMigration(
                "MANUAL", "   ", null, null,
                "schulz", "schulz_compressores_prod", "postgres:16",
                new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pw"),
                eventSink, new AtomicBoolean(false));

        assertFalse(ok, "MANUAL mode with blank SQL must fail-closed, not silently record success");
        assertTrue(events.stream().anyMatch(e ->
                        e.getType() == ContainerEvent.EventType.ERROR
                                && e.getMessage().toLowerCase().contains("required")),
                "an ERROR event explaining the required SQL must be emitted");
        verify(migrationRepository, never()).save(any(DatabaseMigrationRecord.class));
        verify(resourceCounterService, never()).increment(anyString());
        verifyNoInteractions(dockerClient);
    }

    @Test
    void orchestrateMigration_manualModeWithNullSql_failsClosed() {
        boolean ok = service.orchestrateMigration(
                "MANUAL", null, null, null,
                "schulz", "mydb", "postgres:16",
                new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pw"),
                eventSink, new AtomicBoolean(false));

        assertFalse(ok);
        verify(migrationRepository, never()).save(any(DatabaseMigrationRecord.class));
    }

    @Test
    void parseResponseBody_sanitizesVersionStringsForLogAndSseInjection() {
        // Migration API response with CR/LF/ANSI in version strings — these must
        // be stripped before they reach SSE events, logs, or persistence.
        String injected = "{\"statements\":[\"SELECT 1;\"],"
                + "\"sourceVersion\":\"1.0\\n\\u001b[31mFAKE-ERROR\\u001b[0m\","
                + "\"targetVersion\":\"2.0\\r\\nevent: success\"}";

        MigrationService.MigrationResult result = service.parseResponseBody(injected);

        assertNotNull(result.sourceVersion());
        assertFalse(result.sourceVersion().contains("\n"), "newlines must be stripped");
        assertFalse(result.sourceVersion().contains("\r"), "CR must be stripped");
        assertFalse(result.sourceVersion().contains(""), "ANSI ESC must be stripped");
        assertFalse(result.targetVersion().contains("\n"));
        assertFalse(result.targetVersion().contains("\r"));
    }

    @Test
    void parseResponseBody_capsVersionStringLength() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) huge.append('x');
        String json = "{\"statements\":[],\"sourceVersion\":\"" + huge + "\"}";

        MigrationService.MigrationResult result = service.parseResponseBody(json);

        assertNotNull(result.sourceVersion());
        assertTrue(result.sourceVersion().length() <= 128,
                "sourceVersion must be capped to defend against bloated payloads");
    }

    @Test
    void parseResponseBody_capsVersionsIncludedListSize() {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) arr.append(',');
            arr.append("\"v").append(i).append("\"");
        }
        arr.append("]");
        String json = "{\"statements\":[],\"versionsIncluded\":" + arr + "}";

        MigrationService.MigrationResult result = service.parseResponseBody(json);

        assertNotNull(result.versionsIncluded());
        assertTrue(result.versionsIncluded().size() <= 256,
                "versionsIncluded must be capped to defend against memory-amplification payloads");
    }

    // ---- recordMigration ----

    @Test
    void recordMigration_stampsTheActor() {
        when(actorResolver.usernameOrSystem()).thenReturn("alice");

        service.recordMigration("dev_db", "postgres", "API", null);

        var captor = org.mockito.ArgumentCaptor.forClass(DatabaseMigrationRecord.class);
        verify(migrationRepository).save(captor.capture());
        assertEquals("alice", captor.getValue().getMigratedBy());
    }

    @Test
    void recordMigration_legacyMode_leavesActorNull() {
        // legacy password mode has no identity; the tooltip simply omits the user
        when(actorResolver.usernameOrSystem()).thenReturn(null);

        service.recordMigration("dev_db", "postgres", "API", null);

        var captor = org.mockito.ArgumentCaptor.forClass(DatabaseMigrationRecord.class);
        verify(migrationRepository).save(captor.capture());
        assertNull(captor.getValue().getMigratedBy());
    }
}
