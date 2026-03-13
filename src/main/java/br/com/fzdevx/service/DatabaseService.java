package br.com.fzdevx.service;

import br.com.fzdevx.util.InputValidator;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class DatabaseService {

    @Inject
    Config config;

    public boolean hasDatabaseConfig(String repository) {
        boolean globalEnabled = config.getOptionalValue("database.listing.enabled", Boolean.class).orElse(false);
        if (!globalEnabled) {
            return false;
        }
        return config.getOptionalValue("repository.pg-host." + repository, String.class)
                .filter(h -> !h.isBlank())
                .isPresent();
    }

    public boolean isListingEnabled() {
        return config.getOptionalValue("database.listing.enabled", Boolean.class).orElse(false);
    }

    public boolean isDeletionOnExpirationEnabled() {
        return config.getOptionalValue("database.deletion-on-expiration.enabled", Boolean.class).orElse(false);
    }

    public List<String> listDatabases(String repository) {
        List<String> databases = new ArrayList<>();
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT datname FROM pg_database WHERE datistemplate = false AND datname NOT IN ('postgres') ORDER BY datname");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                databases.add(rs.getString("datname"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list databases: " + e.getMessage(), e);
        }
        return databases;
    }

    public void dropDatabase(String repository, String databaseName) {
        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            throw new IllegalArgumentException(nameError.get());
        }

        try (Connection conn = getConnection(repository)) {
            // Terminate active connections first
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ? AND pid <> pg_backend_pid()")) {
                stmt.setString(1, databaseName);
                stmt.execute();
            }

            // Drop the database using a validated + quoted identifier
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS \"" + databaseName + "\"");
            }

            Log.infof("Database '%s' dropped successfully for repository '%s'.", databaseName, repository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to drop database '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    private Connection getConnection(String repository) {
        String host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .orElseThrow(() -> new IllegalStateException("No PG host configured for repository: " + repository));
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        String user = config.getOptionalValue("repository.pg-user." + repository, String.class).orElse("postgres");
        String password = config.getOptionalValue("repository.pg-password." + repository, String.class).orElse("");

        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres?connectTimeout=5&socketTimeout=10";

        try {
            return DriverManager.getConnection(url, user, password);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to PostgreSQL for repository '" + repository + "': " + e.getMessage(), e);
        }
    }

    public Optional<String> getDbEnvVar(String repository) {
        return config.getOptionalValue("repository.pg-db-env-var." + repository, String.class)
                .filter(v -> !v.isBlank());
    }

    public void createDatabase(String repository, String databaseName) {
        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            throw new IllegalArgumentException(nameError.get());
        }

        try (Connection conn = getConnection(repository);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE \"" + databaseName + "\"");
            Log.infof("Database '%s' created successfully for repository '%s'.", databaseName, repository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create database '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    public boolean databaseExists(String repository, String databaseName) {
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM pg_database WHERE datname = ?")) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to check database existence: " + e.getMessage(), e);
        }
    }

    public String getContainerImage(String repository) {
        return config.getOptionalValue("repository.pg-image." + repository, String.class)
                .filter(v -> !v.isBlank())
                .orElse("postgres:latest");
    }

    public record PgConnectionInfo(String host, int port, String user, String password) {}

    public PgConnectionInfo getConnectionInfo(String repository) {
        String host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .orElseThrow(() -> new IllegalStateException("No PG host configured for repository: " + repository));
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        String user = config.getOptionalValue("repository.pg-user." + repository, String.class).orElse("postgres");
        String password = config.getOptionalValue("repository.pg-password." + repository, String.class).orElse("");
        return new PgConnectionInfo(host, port, user, password);
    }
}
