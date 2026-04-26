package br.com.fzdevx.infrastructure.database;

import br.com.fzdevx.application.port.DatabaseReportGeneratorPort;
import br.com.fzdevx.infrastructure.persistence.DatabaseService.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class DatabaseHtmlReportGeneratorAdapter implements DatabaseReportGeneratorPort {

    @Override
    public String generate(String repository, String databaseName,
                           DatabaseHealthInfo health,
                           DatabaseActivity activity,
                           DatabaseTableStats tableStats,
                           ServerHealth serverHealth,
                           List<TopQuery> tempFileQueries) {
        return DatabaseHtmlReportGenerator.generate(repository, databaseName, health, activity, tableStats, serverHealth, tempFileQueries);
    }
}
