package br.com.fzdevx.infrastructure.docker;

import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import br.com.fzdevx.domain.shared.PsqlErrorCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-script verdict and message, independent of how psql was launched. */
class PostRestoreScriptServiceOutcomeTest {

    private final PostRestoreScriptService service = new PostRestoreScriptService();
    private final PostRestoreScriptInfo script = new PostRestoreScriptInfo("01-integration-cleaner.sql", 1, 1100);
    private final List<ContainerEvent> events = new ArrayList<>();

    @Test
    void failureCarriesThePostgresError() {
        PsqlErrorCollector errors = new PsqlErrorCollector();
        errors.accept("psql:/scripts/mandatory/01-integration-cleaner.sql:7: ERROR:  relation \"integration\" does not exist");

        boolean ok = service.reportScriptOutcome(script, "mydb", 3, errors, events::add);

        assertFalse(ok);
        ContainerEvent last = events.getLast();
        assertEquals(ContainerEvent.EventType.ERROR, last.getType());
        assertTrue(last.getMessage().contains("exit code 3"), last.getMessage());
        assertTrue(last.getMessage().contains("relation \"integration\" does not exist"), last.getMessage());
    }

    @Test
    void failureWithoutErrorLinesSaysSo() {
        boolean ok = service.reportScriptOutcome(script, "mydb", 1, new PsqlErrorCollector(), events::add);

        assertFalse(ok);
        assertTrue(events.getLast().getMessage().contains("no PostgreSQL error line"), events.getLast().getMessage());
    }

    @Test
    void zeroExitWithErrorsIsStillAFailure() {
        // psql without ON_ERROR_STOP exits 0 after SQL errors; even with it set,
        // an error line must never pass as success
        PsqlErrorCollector errors = new PsqlErrorCollector();
        errors.accept("psql:/scripts/mandatory/01-integration-cleaner.sql:7: ERROR:  column \"url\" does not exist");

        boolean ok = service.reportScriptOutcome(script, "mydb", 0, errors, events::add);

        assertFalse(ok);
        ContainerEvent last = events.getLast();
        assertEquals(ContainerEvent.EventType.ERROR, last.getType());
        assertTrue(last.getMessage().contains("exited with code 0 but PostgreSQL reported errors"), last.getMessage());
        assertTrue(last.getMessage().contains("column \"url\" does not exist"), last.getMessage());
    }

    @Test
    void onFailureValueIsNormalizedAndValidated() {
        assertEquals("stop", PostRestoreScriptService.normalizeOnFailure(" Stop "));
        assertEquals("continue", PostRestoreScriptService.normalizeOnFailure("CONTINUE"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PostRestoreScriptService.normalizeOnFailure("abort"));
        assertTrue(e.getMessage().contains("'abort'"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> PostRestoreScriptService.normalizeOnFailure(null));
    }

    @Test
    void mandatoryScriptFailureAlwaysStops_evenWithContinue() {
        service.onFailure = "continue";
        PostRestoreScriptInfo mandatory = new PostRestoreScriptInfo("01-integration-cleaner.sql", 1, 1100, true);

        assertTrue(service.stopAfterFailure(mandatory, events::add));
        assertEquals(ContainerEvent.EventType.ERROR, events.getLast().getType());
        assertTrue(events.getLast().getMessage().contains("Mandatory script"), events.getLast().getMessage());
    }

    @Test
    void optionalScriptFailureFollowsOnFailure() {
        PostRestoreScriptInfo optional = new PostRestoreScriptInfo("create-default-user.sql", Integer.MAX_VALUE, 800, false);

        service.onFailure = "continue";
        assertFalse(service.stopAfterFailure(optional, events::add));
        assertEquals(ContainerEvent.EventType.INFO, events.getLast().getType());
        assertTrue(events.getLast().getMessage().contains("continuing"), events.getLast().getMessage());

        service.onFailure = "stop";
        assertTrue(service.stopAfterFailure(optional, events::add));
    }

    @Test
    void cleanRunIsPlainSuccess() {
        boolean ok = service.reportScriptOutcome(script, "mydb", 0, new PsqlErrorCollector(), events::add);

        assertTrue(ok);
        assertEquals("Script '01-integration-cleaner.sql' completed successfully.", events.getLast().getMessage());
    }
}
