package br.com.fzdevx.infrastructure.persistence.jdbc;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Boots the app with the postgres persistence backend (Dev Services PG). */
public class PostgresBackendProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("persistence.backend", "postgres");
    }
}
