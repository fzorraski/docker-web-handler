package br.com.fzdevx.application.port;

import br.com.fzdevx.infrastructure.persistence.DatabaseService;

import java.util.List;

public interface DatabaseReportGeneratorPort {

    String generate(String repository, String databaseName,
                    DatabaseService.DatabaseHealthInfo health,
                    DatabaseService.DatabaseActivity activity,
                    DatabaseService.DatabaseTableStats tableStats,
                    DatabaseService.ServerHealth serverHealth,
                    List<DatabaseService.TopQuery> tempFileQueries);
}
