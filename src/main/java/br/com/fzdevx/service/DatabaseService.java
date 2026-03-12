package br.com.fzdevx.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    public List<String> listDatabases(String repository) {
        String host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .orElseThrow(() -> new IllegalStateException("No PG host configured for repository: " + repository));
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        String user = config.getOptionalValue("repository.pg-user." + repository, String.class).orElse("postgres");
        String password = config.getOptionalValue("repository.pg-password." + repository, String.class).orElse("");

        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres?connectTimeout=5&socketTimeout=10";

        List<String> databases = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
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

    public Optional<String> getDbEnvVar(String repository) {
        return config.getOptionalValue("repository.pg-db-env-var." + repository, String.class)
                .filter(v -> !v.isBlank());
    }
}
