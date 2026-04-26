package br.com.fzdevx.application.usecase;

import br.com.fzdevx.application.port.DatabaseReportGeneratorPort;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class GenerateDatabaseReportUseCase {

    @Inject
    DatabaseReportGeneratorPort reportGenerator;

    @Inject
    DatabaseService databaseService;

    @Inject
    @ConfigProperty(name = "database.query.timeout-seconds", defaultValue = "30")
    int queryTimeoutSeconds;

    public String generateReport(String repository, String databaseName) {
        DatabaseService.DatabaseHealthInfo health = null;
        DatabaseService.DatabaseActivity activity = null;
        DatabaseService.DatabaseTableStats tableStats = null;
        DatabaseService.ServerHealth serverHealth = null;
        List<DatabaseService.TopQuery> tempFileQueries = null;

        try {
            health = databaseService.getDatabaseHealth(repository, databaseName);
        } catch (Exception e) {
            Log.debugf("Report: failed to get health for '%s': %s", databaseName, e.getMessage());
        }

        try {
            activity = databaseService.getDatabaseActivity(repository, databaseName);
        } catch (Exception e) {
            Log.debugf("Report: failed to get activity for '%s': %s", databaseName, e.getMessage());
        }

        try {
            tableStats = databaseService.getDatabaseTableStats(repository, databaseName);
        } catch (Exception e) {
            Log.debugf("Report: failed to get table stats for '%s': %s", databaseName, e.getMessage());
        }

        try {
            serverHealth = databaseService.getServerHealth(repository);
        } catch (Exception e) {
            Log.debugf("Report: failed to get server health for '%s': %s", repository, e.getMessage());
        }

        try {
            tempFileQueries = databaseService.getTopTempFileQueries(repository, databaseName, queryTimeoutSeconds);
        } catch (Exception e) {
            Log.debugf("Report: failed to get temp file queries for '%s': %s", databaseName, e.getMessage());
        }

        return reportGenerator.generate(repository, databaseName, health, activity, tableStats, serverHealth, tempFileQueries);
    }

    public String buildReportFilename(String repository, String databaseName) {
        String safeRepo = sanitize(repository);
        String safeDb = sanitize(databaseName);
        return safeRepo + "-" + safeDb + "-insights.html";
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }
}
