package br.com.fzdevx.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public interface DatabasePort {

    boolean hasDatabaseConfig(String repository);

    boolean isListingEnabled();

    boolean isDeletionOnExpirationEnabled();

    List<String> listDatabases(String repository);

    void dropDatabase(String repository, String databaseName);

    void createDatabase(String repository, String databaseName);

    boolean databaseExists(String repository, String databaseName);

    Optional<String> getDbEnvVar(String repository);

    String getContainerImage(String repository);

    Map<String, Long> getDatabaseSizes(String repository);

    Map<String, Integer> getActiveConnectionCounts(String repository);

    Map<String, Instant> getLastActivityTimes(String repository);

    record ServerHealth(String pgVersion, Instant serverStartedAt, int maxConnections,
                        int totalConnections, long totalDiskSize) {}

    record PgConnectionInfo(String host, int port, String user, String password) {}

    PgConnectionInfo getConnectionInfo(String repository);
}
