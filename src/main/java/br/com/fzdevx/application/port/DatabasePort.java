package br.com.fzdevx.application.port;

import java.util.List;
import java.util.Optional;

// ⚠ SOLID — DIP: port interface abstracting database operations for use cases
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

    record PgConnectionInfo(String host, int port, String user, String password) {}

    PgConnectionInfo getConnectionInfo(String repository);
}
