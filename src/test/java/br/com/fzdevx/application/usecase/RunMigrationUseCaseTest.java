package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.dto.RunMigrationRequest;
import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.domain.model.ContainerEvent;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.infrastructure.config.DatabaseDeletionPolicy;
import br.com.fzdevx.infrastructure.docker.MigrationService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunMigrationUseCaseTest {

    @Mock MigrationService migrationService;
    @Mock DatabaseService databaseService;
    @Mock ManagedDatabaseUsageTracker usageTracker;
    @Mock ManagedDatabaseRepository managedDatabaseRepository;
    @Mock DatabaseDeletionPolicy deletionPolicy;

    @InjectMocks
    RunMigrationUseCase useCase;

    private final List<ContainerEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(migrationService.isEnabled()).thenReturn(true);
        when(databaseService.hasDatabaseConfig("myapp")).thenReturn(true);
        when(databaseService.getContainerImage("myapp")).thenReturn("postgres:16");
        when(databaseService.getConnectionInfo("myapp"))
                .thenReturn(new DatabasePort.PgConnectionInfo("localhost", 5432, "postgres", "pass"));
    }

    private RunMigrationRequest manualRequest() {
        RunMigrationRequest request = new RunMigrationRequest();
        request.setRepository("myapp");
        request.setTargetDatabase("mydb");
        request.setMigrationMode("MANUAL");
        request.setMigrationSql("UPDATE config SET value = '1';");
        return request;
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
    void manualSql_withoutOverwriteRight_isRefused() {
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.NOT_OWNER);

        useCase.execute(manualRequest(), events::add, null);

        assertTrue(lastError().contains("databases you created"), "got: " + lastError());
        verify(migrationService, never()).orchestrateMigration(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void manualSql_withOverwriteRight_runs() {
        stubTargetMetadata(false);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.ALLOWED);
        when(migrationService.orchestrateMigration(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        useCase.execute(manualRequest(), events::add, null);

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS),
                "expected success, last error: " + lastError());
    }

    @Test
    void manualSql_protectedDatabase_isRefused_regardlessOfRights() {
        stubTargetMetadata(true);
        when(deletionPolicy.overwriteVerdict("myapp", "mydb"))
                .thenReturn(DatabaseDeletionPolicy.OverwriteVerdict.PROTECTED);

        useCase.execute(manualRequest(), events::add, null);

        assertTrue(lastError().contains("protected"), "got: " + lastError());
        verify(migrationService, never()).orchestrateMigration(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void apiMode_isNotGated() {
        // curated scripts from the migration API are this flow's purpose
        RunMigrationRequest request = manualRequest();
        request.setMigrationMode("API");
        request.setMigrationSql(null);
        request.setMigrationSourceVersion("20.88.2");
        request.setMigrationTargetVersion("20.88.3");
        when(migrationService.isApiAvailable("myapp")).thenReturn(true);
        when(migrationService.orchestrateMigration(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        useCase.execute(request, events::add, null);

        assertTrue(events.stream().anyMatch(e -> e.getType() == ContainerEvent.EventType.SUCCESS),
                "expected success, last error: " + lastError());
        verifyNoInteractions(deletionPolicy);
    }
}
