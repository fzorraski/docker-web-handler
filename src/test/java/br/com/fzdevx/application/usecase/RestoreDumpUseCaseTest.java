package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RestoreDumpRequest;
import br.com.fzdevx.application.port.AuditLogger;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.DatabaseDump;
import br.com.fzdevx.domain.model.ManagedDatabase;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers the overwrite gate in {@link RestoreDumpUseCase#execute}: restoring
 * into an EXISTING database destroys its contents (--clean), so it follows the
 * deletion rule. The docker-driven restore itself is not exercised here.
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
}
