package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the post-restore script rules that once let an
 * unsanitized production copy go live: discovery marks mandatory scripts, psql
 * stops on the first error, a failed mandatory script always stops the run,
 * and the on-failure setting is validated at startup. Runs psql-free: the
 * local runner is exercised through the "script file not found" path.
 */
class PostRestoreScriptServiceTest {

    private static final DatabasePort.PgConnectionInfo PG =
            new DatabasePort.PgConnectionInfo("db.internal", 5433, "wms", "s3cret");

    @TempDir Path root;
    Path mandatoryDir;
    Path optionalDir;

    PostRestoreScriptService service;
    final List<ContainerEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mandatoryDir = Files.createDirectories(root.resolve("mandatory"));
        optionalDir = Files.createDirectories(root.resolve("optional"));

        Config config = mock(Config.class);
        when(config.getOptionalValue(anyString(), eq(String.class))).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.post-restore-mandatory-dir.myapp", String.class))
                .thenReturn(Optional.of(mandatoryDir.toString()));
        when(config.getOptionalValue("repository.post-restore-optional-dir.myapp", String.class))
                .thenReturn(Optional.of(optionalDir.toString()));

        service = new PostRestoreScriptService();
        service.config = config;
        service.featureEnabled = true;
        service.onFailure = "stop";
        service.validateConfiguration();
    }

    private void script(Path dir, String name) throws Exception {
        Files.writeString(dir.resolve(name), "SELECT 1;");
    }

    private List<String> errorMessages() {
        return events.stream()
                .filter(e -> e.getType() == ContainerEvent.EventType.ERROR)
                .map(ContainerEvent::getMessage)
                .toList();
    }

    // ----- discovery -------------------------------------------------------

    @Test
    void discovery_marksMandatoryScripts_andOrdersByNumericPrefix() throws Exception {
        script(mandatoryDir, "10-second.sql");
        script(mandatoryDir, "02-first.sql");
        script(mandatoryDir, "zz-last.sql");
        script(mandatoryDir, "README.txt");
        Files.createDirectories(mandatoryDir.resolve("nested.sql"));
        script(optionalDir, "create-user.sql");

        List<PostRestoreScriptInfo> mandatory = service.discoverMandatoryScripts("myapp");
        List<PostRestoreScriptInfo> optional = service.discoverOptionalScripts("myapp");

        assertEquals(List.of("02-first.sql", "10-second.sql", "zz-last.sql"),
                mandatory.stream().map(PostRestoreScriptInfo::getFilename).toList());
        assertTrue(mandatory.stream().allMatch(PostRestoreScriptInfo::isMandatory));
        assertEquals(1, optional.size());
        assertFalse(optional.getFirst().isMandatory());
    }

    @Test
    void discovery_isEmptyWhenTheRepositoryHasNoDirectoryConfigured() {
        assertTrue(service.discoverMandatoryScripts("other-repo").isEmpty());
        assertTrue(service.discoverOptionalScripts("other-repo").isEmpty());
    }

    @Test
    void resolve_keepsEveryMandatoryScript_andOnlySelectedValidOptionalOnes() throws Exception {
        script(mandatoryDir, "01-clean.sql");
        script(optionalDir, "create-user.sql");
        script(optionalDir, "stop-jobs.sql");

        List<PostRestoreScriptInfo> toRun = service.resolveScriptsToExecute("myapp",
                List.of("stop-jobs.sql", "../../etc/passwd.sql", "unknown.sql"));

        assertEquals(List.of("01-clean.sql", "stop-jobs.sql"),
                toRun.stream().map(PostRestoreScriptInfo::getFilename).toList());
        assertTrue(toRun.get(0).isMandatory());
        assertFalse(toRun.get(1).isMandatory());
    }

    @Test
    void resolve_runsMandatoryScriptsEvenWhenNothingOptionalIsSelected() throws Exception {
        script(mandatoryDir, "01-clean.sql");

        assertEquals(1, service.resolveScriptsToExecute("myapp", null).size());
        assertEquals(1, service.resolveScriptsToExecute("myapp", List.of()).size());
    }

    // ----- psql invocation -------------------------------------------------

    @Test
    void psqlCommand_stopsOnFirstError_andTargetsTheRightDatabase() {
        List<String> cmd = PostRestoreScriptService.psqlCommand(PG, "mydb", "/scripts/mandatory/01-clean.sql");

        assertEquals("psql", cmd.getFirst());
        int v = cmd.indexOf("-v");
        assertTrue(v > 0 && "ON_ERROR_STOP=1".equals(cmd.get(v + 1)), "ON_ERROR_STOP missing: " + cmd);
        assertEquals("db.internal", cmd.get(cmd.indexOf("-h") + 1));
        assertEquals("5433", cmd.get(cmd.indexOf("-p") + 1));
        assertEquals("wms", cmd.get(cmd.indexOf("-U") + 1));
        assertEquals("mydb", cmd.get(cmd.indexOf("-d") + 1));
        assertEquals("/scripts/mandatory/01-clean.sql", cmd.get(cmd.indexOf("-f") + 1));
        assertFalse(cmd.contains("s3cret"), "the password travels via PGPASSWORD, never on the command line");
    }

    // ----- local runner: failure policy through the real loop ---------------

    @Test
    void executeScripts_mandatoryScriptMissing_stopsTheRun_evenWithContinue() throws Exception {
        service.onFailure = "continue";
        script(optionalDir, "after.sql");
        PostRestoreScriptInfo missingMandatory = new PostRestoreScriptInfo("01-clean.sql", 1, 10, true);
        PostRestoreScriptInfo after = new PostRestoreScriptInfo("after.sql", Integer.MAX_VALUE, 10, false);

        boolean mayProceed = service.executeScripts(List.of(missingMandatory, after), PG, "mydb", "none",
                "myapp", events::add, new AtomicBoolean(false));

        assertFalse(mayProceed);
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("Script file not found: 01-clean.sql")), errorMessages().toString());
        assertTrue(errorMessages().stream().anyMatch(m -> m.contains("Mandatory script '01-clean.sql' failed")), errorMessages().toString());
        assertTrue(events.stream().noneMatch(e -> e.getMessage().contains("after.sql")), "nothing runs after a mandatory failure");
    }

    @Test
    void executeScripts_optionalScriptMissing_withContinue_keepsGoing() {
        service.onFailure = "continue";
        PostRestoreScriptInfo first = new PostRestoreScriptInfo("first.sql", 1, 10, false);
        PostRestoreScriptInfo second = new PostRestoreScriptInfo("second.sql", 2, 10, false);

        boolean mayProceed = service.executeScripts(List.of(first, second), PG, "mydb", "none",
                "myapp", events::add, new AtomicBoolean(false));

        assertTrue(mayProceed);
        assertEquals(2, errorMessages().stream().filter(m -> m.startsWith("Script file not found")).count());
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains("Optional script 'first.sql' failed; continuing")));
    }

    @Test
    void executeScripts_optionalScriptMissing_withStop_stopsTheRun() {
        service.onFailure = "stop";
        PostRestoreScriptInfo first = new PostRestoreScriptInfo("first.sql", 1, 10, false);
        PostRestoreScriptInfo second = new PostRestoreScriptInfo("second.sql", 2, 10, false);

        boolean mayProceed = service.executeScripts(List.of(first, second), PG, "mydb", "none",
                "myapp", events::add, new AtomicBoolean(false));

        assertFalse(mayProceed);
        assertTrue(events.stream().noneMatch(e -> e.getMessage().contains("second.sql")));
    }

    @Test
    void executeScripts_cancelledBeforeAScript_stops() {
        PostRestoreScriptInfo first = new PostRestoreScriptInfo("first.sql", 1, 10, true);

        boolean mayProceed = service.executeScripts(List.of(first), PG, "mydb", "none",
                "myapp", events::add, new AtomicBoolean(true));

        assertFalse(mayProceed);
        assertTrue(errorMessages().getFirst().contains("cancelled"));
    }

    @Test
    void executeScripts_withNothingToRun_proceeds() {
        assertTrue(service.executeScripts(List.of(), PG, "mydb", "none", "myapp", events::add, new AtomicBoolean(false)));
        assertEquals("No post-restore scripts to execute.", events.getFirst().getMessage());
    }

    // ----- startup validation ---------------------------------------------

    @Test
    void validateConfiguration_normalizesKnownValues() {
        service.onFailure = "  Continue ";
        service.validateConfiguration();
        assertEquals("continue", service.getOnFailure());
    }

    @Test
    void validateConfiguration_refusesUnknownValues() {
        service.onFailure = "abort";
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.validateConfiguration());
        assertTrue(e.getMessage().contains("post-restore-scripts.on-failure"), e.getMessage());
    }
}
