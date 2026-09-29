package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.model.PostRestoreScriptInfo;
import br.com.fzdevx.infrastructure.config.ActorResolver;
import br.com.fzdevx.infrastructure.config.DatabaseDeletionPolicy;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.docker.PostRestoreScriptService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.infrastructure.persistence.DumpStorageService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.persistence.SnapshotStorageService;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers two rules of {@link RestoreDumpUseCase#execute}.
 *
 * <p>The overwrite gate: restoring into an EXISTING database destroys its
 * contents (--clean), so it follows the deletion rule.</p>
 *
 * <p>The no-leftover rule: once the dump has landed in the target, a restore
 * that fails or is cancelled (typically in the mandatory post-restore scripts)
 * drops the database and audits the outcome, so an unsanitized copy of the
 * source never surfaces in the "existing database" list.</p>
 *
 * <p>The docker/local restore command itself is stubbed via {@code runRestore}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestoreDumpUseCaseTest {

    private static final String DUMP_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock DumpStorageService dumpStorageService;
    @Mock AuditLogger auditLogger;
    @Mock DatabaseService databaseService;
    @Mock PostRestoreScriptService postRestoreScriptService;
    @Mock MigrationService migrationService;
    @Mock SnapshotStorageService snapshotStorageService;
    @Mock DockerClient dockerClient;
    @Mock ResourceCounterService resourceCounterService;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock ListManagedDatabasesUseCase listManagedDatabasesUseCase;
    @Mock ActorResolver actorResolver;
    @Mock DatabaseDeletionPolicy deletionPolicy;

    @Spy
    @InjectMocks
    RestoreDumpUseCase useCase;

    private final List<ContainerEvent> events = new ArrayList<>();

    private RestoreDumpRequest request() {
        RestoreDumpRequest request = new RestoreDumpRequest();
        request.setDumpId(DUMP_ID);
        request.setRepository("myapp");
        request.setTargetDatabase("mydb");
        return request;
    }

    private void stubValidRestore(boolean databaseExists) {
        when(databaseService.hasDatabaseConfig("myapp")).thenReturn(true);
        when(databaseService.databaseExists("myapp", "mydb")).thenReturn(databaseExists);
        DatabaseDump dump = new DatabaseDump();
        dump.setOriginalFilename("backup.sql");
        dump.setFormat(DatabaseDump.Format.SQL);
        when(dumpStorageService.findById(DUMP_ID)).thenReturn(Optional.of(dump));
    }

    private ManagedDatabase stubTargetMetadata(boolean protectedFlag) {
        ManagedDatabase md = new ManagedDatabase("myapp", "mydb");
        md.setProtectedFlag(protectedFlag);
        when(managedDatabaseRepository.find("myapp", "mydb")).thenReturn(Optional.of(md));
        return md;
    }

    private String lastError() {
        return events.stream()
                .filter(e -> e.getType() == ContainerEvent.EventType.ERROR)
                .reduce((a, b) -> b)
                .map(ContainerEvent::getMessage)
                .orElse("");
    }

    @Test
    void execute_existingDatabase_withoutOverwriteRight_isRefused() {
        stubValidRestore(true);
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.NOT_OWNER);

        boolean success = useCase.execute(request(), events::add);

        assertFalse(success);
        assertTrue(lastError().contains("overwrite"), "expected the overwrite refusal, got: " + lastError());
        // nothing was touched: no drop, no creation, no restore container
        verify(databaseService, never()).dropDatabase(any(), any());
        verify(databaseService, never()).createDatabase(any(), any(), any());
        verifyNoInteractions(dockerClient);
    }

    @Test
    void execute_existingDatabase_withOverwriteRight_passesTheGate() {
        stubValidRestore(true);
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.ALLOWED);

        boolean success = useCase.execute(request(), events::add);

        // the docker-driven restore itself is unmocked and fails later; what
        // matters here is that the failure is NOT the overwrite refusal
        assertFalse(success);
        assertFalse(lastError().contains("overwrite"), "gate refused an allowed overwrite: " + lastError());
        verify(deletionPolicy).overwriteVerdict("myapp", "mydb");
    }

    @Test
    void execute_protectedDatabase_isNeverOverwritten_evenWithFullDelete() {
        stubValidRestore(true);
        stubTargetMetadata(true);
        // full DATABASE_DELETE would pass the ownership gate - protection wins anyway
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.PROTECTED);

        boolean success = useCase.execute(request(), events::add);

        assertFalse(success);
        assertTrue(lastError().contains("protected"), "expected the protection refusal, got: " + lastError());
        verifyNoInteractions(dockerClient);
    }

    @Test
    void execute_newDatabase_neverConsultsTheOverwriteGate() {
        stubValidRestore(false);

        useCase.execute(request(), events::add);

        // restoring into a database that does not exist destroys nothing
        verify(deletionPolicy, never()).overwriteVerdict(any(), any());
    }

    // ----- no-leftover rule -------------------------------------------------

    /** The restore command itself succeeds; what happens next is up to each test. */
    private void stubRestoreLands() throws Exception {
        when(databaseService.getContainerImage("myapp")).thenReturn("none");
        when(databaseService.getConnectionInfo("myapp"))
                .thenReturn(new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "secret"));
        doReturn(new RestoreDumpUseCase.RestoreResult(0, 0)).when(useCase)
                .runRestore(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    private void stubMandatoryScripts(boolean succeed) {
        when(postRestoreScriptService.isEnabled()).thenReturn(true);
        when(postRestoreScriptService.getOnFailure()).thenReturn("stop");
        when(postRestoreScriptService.resolveScriptsToExecute(eq("myapp"), any()))
                .thenReturn(List.of(new PostRestoreScriptInfo("01-integration-cleaner.sql", 1, 1100)));
        when(postRestoreScriptService.executeScripts(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (!succeed) {
                        // the service reports through the sink it was handed, like the real one
                        Consumer<ContainerEvent> sink = inv.getArgument(5);
                        sink.accept(ContainerEvent.error("Running Scripts",
                                "Script '01-integration-cleaner.sql' failed with exit code 3."));
                    }
                    return succeed;
                });
    }

    private String auditDetail(String action) {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(eq(action), eq("mydb"), captor.capture());
        return captor.getValue();
    }

    @Test
    void execute_mandatoryScriptFails_onDatabaseCreatedByThisRun_dropsItAndAudits() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        stubMandatoryScripts(false);
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        boolean success = useCase.execute(request, events::add);

        assertFalse(success);
        verify(databaseService).createDatabase("myapp", "mydb", null);
        verify(databaseService).dropDatabase("myapp", "mydb");
        verify(managedDatabaseRepository).delete("myapp", "mydb");
        verify(listManagedDatabasesUseCase).invalidateCache("myapp");
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains("Dropped database 'mydb'")),
                "the user must be told the database was dropped");
        String detail = auditDetail("DATABASE_RESTORE_FAILED");
        assertTrue(detail.contains("source=backup.sql"), detail);
        assertTrue(detail.contains("database=dropped"), detail);
        assertTrue(detail.contains("reason=Script '01-integration-cleaner.sql' failed"), detail);
        verify(auditLogger, never()).log(eq("DATABASE_RESTORE"), any(), any());
        verify(dumpStorageService, never()).markUsed(any());
    }

    @Test
    void execute_mandatoryScriptFails_onOverwrittenDatabase_dropsItToo() throws Exception {
        // the previous contents were already destroyed by the restore, so nothing
        // of value is lost, and what is there now is unsanitized production data
        stubValidRestore(true);
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.ALLOWED);
        stubRestoreLands();
        stubMandatoryScripts(false);

        boolean success = useCase.execute(request(), events::add);

        assertFalse(success);
        verify(databaseService, never()).createDatabase(any(), any(), any());
        verify(databaseService).dropDatabase("myapp", "mydb");
        assertTrue(auditDetail("DATABASE_RESTORE_FAILED").contains("database=dropped"));
    }

    @Test
    void execute_restoreCommandFails_dropsThePartiallyRestoredDatabase() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        doReturn(new RestoreDumpUseCase.RestoreResult(1, 0,
                "1 error(s): pg_restore: error: could not execute query: ERROR:  role \"wms\" does not exist"))
                .when(useCase).runRestore(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        boolean success = useCase.execute(request, events::add);

        assertFalse(success);
        verify(postRestoreScriptService, never()).executeScripts(any(), any(), any(), any(), any(), any(), any());
        verify(databaseService).dropDatabase("myapp", "mydb");
        String detail = auditDetail("DATABASE_RESTORE_FAILED");
        assertTrue(detail.contains("database=dropped"), detail);
        assertTrue(detail.contains("reason=Restore process exited with code 1"), detail);
        assertTrue(detail.contains("role \"wms\" does not exist"), "the audit reason must carry the PostgreSQL error: " + detail);
    }

    @Test
    void execute_restoreCommandFailsWithoutErrorLine_reportsTheLastOutput() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        doReturn(new RestoreDumpUseCase.RestoreResult(1, 0, null,
                List.of("SET", "Command was: SET transaction_timeout = 0;")))
                .when(useCase).runRestore(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        assertFalse(useCase.execute(request, events::add));

        assertTrue(lastError().contains("last output: SET | Command was: SET transaction_timeout = 0;"), lastError());
        assertFalse(lastError().contains("Check logs above"), lastError());
    }

    @Test
    void execute_dropFails_flagsTheDatabaseAsUnsanitized() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        stubMandatoryScripts(false);
        doThrow(new RuntimeException("permission denied")).when(databaseService).dropDatabase("myapp", "mydb");
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        boolean success = useCase.execute(request, events::add);

        assertFalse(success);
        assertTrue(lastError().contains("UNSANITIZED"), lastError());
        assertTrue(lastError().contains("drop it manually"), lastError());
        verify(managedDatabaseRepository, never()).delete(any(), any());
        assertTrue(auditDetail("DATABASE_RESTORE_FAILED").contains("database=drop-failed"));
    }

    @Test
    void execute_cancelledDuringScripts_dropsAndAuditsTheCancellation() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        when(postRestoreScriptService.isEnabled()).thenReturn(true);
        when(postRestoreScriptService.getOnFailure()).thenReturn("stop");
        when(postRestoreScriptService.resolveScriptsToExecute(eq("myapp"), any()))
                .thenReturn(List.of(new PostRestoreScriptInfo("01-integration-cleaner.sql", 1, 1100)));
        when(postRestoreScriptService.executeScripts(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    assertTrue(useCase.cancel("myapp", "mydb"), "restore must be registered as active");
                    return false;
                });
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        boolean success = useCase.execute(request, events::add);

        assertFalse(success);
        verify(databaseService).dropDatabase("myapp", "mydb");
        assertTrue(auditDetail("DATABASE_RESTORE_CANCELLED").contains("database=dropped"));
        verify(auditLogger, never()).log(eq("DATABASE_RESTORE_FAILED"), any(), any());
    }

    @Test
    void execute_failsBeforeDataLands_leavesTheExistingDatabaseAlone() throws Exception {
        stubValidRestore(true);
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.ALLOWED);
        when(dumpStorageService.prepareForRestore(any())).thenThrow(new RuntimeException("disk full"));

        boolean success = useCase.execute(request(), events::add);

        assertFalse(success);
        verify(databaseService, never()).dropDatabase(any(), any());
        verify(useCase, never()).runRestore(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        String detail = auditDetail("DATABASE_RESTORE_FAILED");
        assertTrue(detail.contains("database=untouched"), detail);
        assertTrue(detail.contains("disk full"), detail);
    }

    @Test
    void execute_mandatoryScriptFails_isFatalRegardlessOfOnFailureSetting() throws Exception {
        // the service decides whether the run may proceed; the use case must not
        // second-guess it by consulting on-failure itself
        stubValidRestore(false);
        stubRestoreLands();
        stubMandatoryScripts(false);
        when(postRestoreScriptService.getOnFailure()).thenReturn("continue");
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        assertFalse(useCase.execute(request, events::add));

        verify(databaseService).dropDatabase("myapp", "mydb");
        verify(migrationService, never()).orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        auditDetail("DATABASE_RESTORE_FAILED");
    }

    @Test
    void restoreResult_failureDetail_prefersErrorsThenTailThenNothing() {
        assertEquals(". PostgreSQL reported 1 error(s): ERROR:  boom",
                new RestoreDumpUseCase.RestoreResult(1, 0, "1 error(s): ERROR:  boom", List.of("ignored")).failureDetail());
        assertEquals(". No error line was recognised; last output: SET | COPY 3",
                new RestoreDumpUseCase.RestoreResult(1, 0, null, List.of("SET", "COPY 3")).failureDetail());
        assertEquals(". No output was captured.",
                new RestoreDumpUseCase.RestoreResult(1, 0).failureDetail());
    }

    @Test
    void execute_completesSuccessfully_keepsTheDatabase() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        stubMandatoryScripts(true);
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);

        boolean success = useCase.execute(request, events::add);

        assertTrue(success, lastError());
        verify(databaseService, never()).dropDatabase(any(), any());
        verify(managedDatabaseRepository, never()).delete(any(), any());
        verify(auditLogger).log(eq("DATABASE_RESTORE"), eq("mydb"), contains("source=backup.sql"));
        verify(auditLogger, never()).log(eq("DATABASE_RESTORE_FAILED"), any(), any());
        verify(auditLogger, never()).log(eq("DATABASE_RESTORE_CANCELLED"), any(), any());
        verify(dumpStorageService).markUsed(DUMP_ID);
    }

    @Test
    void execute_migrationFailsAfterScriptsSanitized_keepsTheDatabase() throws Exception {
        stubValidRestore(false);
        stubRestoreLands();
        stubMandatoryScripts(true);
        when(migrationService.isEnabled()).thenReturn(true);
        when(migrationService.orchestrateMigration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<ContainerEvent> sink = inv.getArgument(8);
                    sink.accept(ContainerEvent.error("Running Migration", "Migration failed: syntax error"));
                    return false;
                });
        RestoreDumpRequest request = request();
        request.setCreateDatabase(true);
        request.setMigrationMode("MANUAL");
        request.setMigrationSql("ALTER TABLE t ADD COLUMN c int;");

        boolean success = useCase.execute(request, events::add);

        assertFalse(success);
        // the data is already sanitized, so nothing dangerous is left behind
        verify(databaseService, never()).dropDatabase(any(), any());
        assertTrue(events.stream().anyMatch(e -> e.getMessage().contains("kept")));
        String detail = auditDetail("DATABASE_RESTORE_FAILED");
        assertTrue(detail.contains("database=kept"), detail);
        assertTrue(detail.contains("reason=Migration failed"), detail);
    }
}
