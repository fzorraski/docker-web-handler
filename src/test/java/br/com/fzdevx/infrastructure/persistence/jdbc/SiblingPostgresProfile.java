package br.com.fzdevx.infrastructure.persistence.jdbc;

import java.util.Map;

/**
 * Postgres backend plus two repositories that point at the same (fake) PostgreSQL server.
 * database.listing stays off so nothing tries to connect to it.
 */
public class SiblingPostgresProfile extends PostgresBackendProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "persistence.backend", "postgres",
                "allowed.run.repositories", "sib-a,sib-b,solo",
                "repository.pg-host.sib-a", "dbhost",
                "repository.pg-host.sib-b", "DBHOST",
                "repository.pg-port.sib-b", "5432",
                "repository.pg-host.solo", "elsewhere",
                "database.listing.enabled", "false");
    }
}
