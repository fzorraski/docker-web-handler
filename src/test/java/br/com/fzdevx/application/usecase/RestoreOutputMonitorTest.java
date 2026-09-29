package br.com.fzdevx.application.usecase;

import br.com.fzdevx.domain.model.ContainerEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The restore output monitor throttles progress to the latest line, which once
 * hid the PostgreSQL error behind the "Command was: ..." that follows it. Error
 * lines and their context must reach the dialog untouched, and the tail must be
 * available for the final message.
 */
class RestoreOutputMonitorTest {

    private final RestoreDumpUseCase useCase = new RestoreDumpUseCase();
    private final List<ContainerEvent> events = new ArrayList<>();

    private RestoreDumpUseCase.OutputMonitorResult run(String... lines) throws InterruptedException {
        String text = String.join("\n", lines) + "\n";
        RestoreDumpUseCase.OutputMonitor monitor = useCase.startOutputMonitor(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "Restoring", events::add);
        return monitor.awaitCompletion(events::add, "Restoring");
    }

    private List<String> progressMessages() {
        return events.stream()
                .filter(e -> e.getType() == ContainerEvent.EventType.PROGRESS)
                .map(ContainerEvent::getMessage)
                .toList();
    }

    @Test
    void errorAndContextLinesAreForwardedVerbatim_notOnlyTheLatestLine() throws Exception {
        String error = "pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter \"transaction_timeout\"";
        String context = "Command was: SET transaction_timeout = 0;";

        RestoreDumpUseCase.OutputMonitorResult result = run("SET", "SET", error, context, "CREATE TABLE");

        List<String> progress = progressMessages();
        assertTrue(progress.contains(error), "error line must be its own event: " + progress);
        assertTrue(progress.contains(context), "context line must be its own event: " + progress);
        assertEquals(1, result.errors().count());
        assertTrue(result.errors().summary().contains("transaction_timeout"));
        assertEquals(5, result.lineCount());
    }

    @Test
    void plainOutputIsOnlyReportedThrottled() throws Exception {
        run("SET", "CREATE TABLE", "COPY 12");

        List<String> progress = progressMessages();
        // no plain line is forwarded verbatim; the throttled reporter appends a counter
        assertTrue(progress.stream().noneMatch(m -> m.equals("SET") || m.equals("CREATE TABLE") || m.equals("COPY 12")), progress.toString());
        assertTrue(progress.getLast().startsWith("Restore output finished (3 lines)"), progress.toString());
    }

    @Test
    void keepsTheLastFiveLinesAsTail() throws Exception {
        String[] lines = IntStream.rangeClosed(1, 8).mapToObj(i -> "line " + i).toArray(String[]::new);

        RestoreDumpUseCase.OutputMonitorResult result = run(lines);

        assertEquals(List.of("line 4", "line 5", "line 6", "line 7", "line 8"), result.outputTail());
    }

    @Test
    void stillCountsIgnoredWarningsFromPgRestore() throws Exception {
        RestoreDumpUseCase.OutputMonitorResult result = run(
                "pg_restore: warning: errors ignored on restore: 3");

        assertEquals(3, result.warningsIgnored());
        assertTrue(result.errors().isEmpty(), "an 'errors ignored' summary is not an error line");
    }
}
