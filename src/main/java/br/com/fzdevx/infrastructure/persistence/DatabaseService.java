package br.com.fzdevx.infrastructure.persistence;

import br.com.fzdevx.application.port.DatabasePort;
import br.com.fzdevx.domain.shared.InputValidator;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DatabaseService implements DatabasePort {

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


            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT quote_ident(?)")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    String quoted = rs.getString(1);
                    try (Statement ddl = conn.createStatement()) {
                        ddl.execute("DROP DATABASE IF EXISTS " + quoted);
                    }
                }
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


        try (Connection conn = getConnection(repository)) {
            String quoted;
            try (PreparedStatement ps = conn.prepareStatement("SELECT quote_ident(?)")) {
                ps.setString(1, databaseName);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    quoted = rs.getString(1);
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE DATABASE " + quoted);
            }
            Log.infof("Database '%s' created successfully for repository '%s'.", databaseName, repository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create database '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    public Map<String, Long> getDatabaseSizes(String repository) {
        Map<String, Long> sizes = new HashMap<>();
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT datname, pg_database_size(datname) AS size_bytes FROM pg_database "
                             + "WHERE datistemplate = false AND datname NOT IN ('postgres')");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                sizes.put(rs.getString("datname"), rs.getLong("size_bytes"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get database sizes: " + e.getMessage(), e);
        }
        return sizes;
    }

    public Map<String, Integer> getActiveConnectionCounts(String repository) {
        Map<String, Integer> counts = new HashMap<>();
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT datname, COUNT(*) AS cnt FROM pg_stat_activity "
                             + "WHERE datname IS NOT NULL GROUP BY datname");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getString("datname"), rs.getInt("cnt"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get active connection counts: " + e.getMessage(), e);
        }
        return counts;
    }

    public Map<String, Instant> getLastActivityTimes(String repository) {
        Map<String, Instant> times = new HashMap<>();
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT datname, MAX(GREATEST("
                             + "COALESCE(state_change, '1970-01-01'::timestamptz), "
                             + "COALESCE(query_start, '1970-01-01'::timestamptz), "
                             + "COALESCE(xact_start, '1970-01-01'::timestamptz)"
                             + ")) AS last_activity FROM pg_stat_activity "
                             + "WHERE datname IS NOT NULL AND datname NOT IN ('postgres') "
                             + "GROUP BY datname");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("last_activity");
                if (ts != null) {
                    Instant instant = ts.toInstant();
                    if (instant.isAfter(Instant.EPOCH)) {
                        times.put(rs.getString("datname"), instant);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get last activity times: " + e.getMessage(), e);
        }
        return times;
    }

    public record DatabaseHealthInfo(
            long sizeBytes,
            int activeConnections,
            int waitingConnections,
            int longRunningQueries,
            double cacheHitRatio,
            long xactCommit,
            long xactRollback,
            long tempBytes,
            int tempFiles,
            long deadTuples,
            long txIdAge
    ) {}

    public DatabaseHealthInfo getDatabaseHealth(String repository, String databaseName) {
        try (Connection conn = getConnection(repository)) {
            long size = 0;
            int active = 0;
            int waiting = 0;
            int longRunning = 0;
            double cacheHit = 0;
            long commits = 0;
            long rollbacks = 0;
            long tempBytes = 0;
            int tempFiles = 0;
            long deadTuples = 0;
            long txAge = 0;

            // Combined query: size, connections, pg_stat_database, and txid age in 1 round-trip
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT "
                            + "pg_database_size(d.datname) AS size_bytes, "
                            + "(SELECT COUNT(*) FROM pg_stat_activity WHERE datname = d.datname) AS active_conn, "
                            + "(SELECT COUNT(*) FROM pg_stat_activity WHERE datname = d.datname AND wait_event_type = 'Lock') AS waiting_conn, "
                            + "(SELECT COUNT(*) FROM pg_stat_activity WHERE datname = d.datname AND state = 'active' "
                            + "AND now() - query_start > interval '5 seconds') AS long_running, "
                            + "COALESCE(s.blks_hit, 0) AS blks_hit, COALESCE(s.blks_read, 0) AS blks_read, "
                            + "COALESCE(s.xact_commit, 0) AS xact_commit, COALESCE(s.xact_rollback, 0) AS xact_rollback, "
                            + "COALESCE(s.temp_bytes, 0) AS temp_bytes, COALESCE(s.temp_files, 0) AS temp_files, "
                            + "age(d.datfrozenxid) AS tx_age "
                            + "FROM pg_database d "
                            + "LEFT JOIN pg_stat_database s ON s.datname = d.datname "
                            + "WHERE d.datname = ?")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        size = rs.getLong("size_bytes");
                        active = rs.getInt("active_conn");
                        waiting = rs.getInt("waiting_conn");
                        longRunning = rs.getInt("long_running");
                        long hit = rs.getLong("blks_hit");
                        long read = rs.getLong("blks_read");
                        cacheHit = (hit + read) > 0 ? (double) hit / (hit + read) * 100.0 : 100.0;
                        commits = rs.getLong("xact_commit");
                        rollbacks = rs.getLong("xact_rollback");
                        tempBytes = rs.getLong("temp_bytes");
                        tempFiles = rs.getInt("temp_files");
                        txAge = rs.getLong("tx_age");
                    }
                }
            }

            // Dead tuples — SUM across all tables visible from pg_stat_user_tables
            // This requires connecting to the target database, not postgres
            try (Connection dbConn = getTargetDbConnection(repository, databaseName);
                 PreparedStatement stmt = dbConn.prepareStatement(
                         "SELECT COALESCE(SUM(n_dead_tup), 0) FROM pg_stat_user_tables");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) deadTuples = rs.getLong(1);
            } catch (Exception e) {
                Log.debugf("Could not fetch dead tuples for '%s': %s", databaseName, e.getMessage());
            }

            return new DatabaseHealthInfo(size, active, waiting, longRunning,
                    Math.round(cacheHit * 100.0) / 100.0, commits, rollbacks, tempBytes, tempFiles,
                    deadTuples, txAge);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get database health for '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    public record ActiveSession(String user, String state, String query,
                                    String clientAddr, long durationSeconds, String waitEventType) {}

    public record UserConnectionCount(String user, int connections, int active, int idle) {}

    public record TopQuery(String queryText, long calls, double totalTimeMs, double meanTimeMs, long rows,
                           long tempBlksRead, long tempBlksWritten) {}

    public record BlockedProcess(int blockedPid, String blockedUser, String blockedQuery,
                                  String blockedMode, String relName,
                                  int blockingPid, String blockingUser, String blockingQuery,
                                  String blockingMode, long waitingSeconds) {}

    public record DatabaseActivity(
            List<ActiveSession> sessions,
            List<UserConnectionCount> topUsers,
            List<TopQuery> topQueries,
            List<BlockedProcess> blockedProcesses,
            boolean pgStatStatementsAvailable
    ) {}

    public DatabaseActivity getDatabaseActivity(String repository, String databaseName) {
        List<ActiveSession> sessions = new ArrayList<>();
        List<UserConnectionCount> users = new ArrayList<>();
        List<TopQuery> topQueries = new ArrayList<>();
        List<BlockedProcess> blockedProcesses = new ArrayList<>();
        boolean pgssAvailable = false;

        // Sessions from pg_stat_activity (connected to postgres db, filtering by datname)
        try (Connection conn = getConnection(repository)) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT usename, state, query, client_addr, "
                            + "EXTRACT(EPOCH FROM (now() - query_start))::bigint AS duration_seconds, "
                            + "wait_event_type "
                            + "FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid() "
                            + "ORDER BY query_start ASC NULLS LAST "
                            + "LIMIT 20")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        sessions.add(new ActiveSession(
                                rs.getString("usename"),
                                rs.getString("state"),
                                normalizeQuery(rs.getString("query")),
                                rs.getString("client_addr"),
                                rs.getLong("duration_seconds"),
                                rs.getString("wait_event_type")
                        ));
                    }
                }
            }

            // Top users by connection count
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT usename, COUNT(*) AS total, "
                            + "COUNT(*) FILTER (WHERE state = 'active') AS active, "
                            + "COUNT(*) FILTER (WHERE state = 'idle') AS idle "
                            + "FROM pg_stat_activity "
                            + "WHERE datname = ? AND pid <> pg_backend_pid() "
                            + "GROUP BY usename ORDER BY total DESC LIMIT 10")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        users.add(new UserConnectionCount(
                                rs.getString("usename"),
                                rs.getInt("total"),
                                rs.getInt("active"),
                                rs.getInt("idle")
                        ));
                    }
                }
            }
            // Blocked processes (reuse same postgres connection)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT blocked.pid AS blocked_pid, blocked.usename AS blocked_user, "
                            + "blocked.query AS blocked_query, blocked_locks.mode AS blocked_mode, "
                            + "c.relname, "
                            + "blocking.pid AS blocking_pid, blocking.usename AS blocking_user, "
                            + "blocking.query AS blocking_query, blocking_locks.mode AS blocking_mode, "
                            + "EXTRACT(EPOCH FROM (now() - blocked.state_change))::bigint AS waiting_seconds "
                            + "FROM pg_locks blocked_locks "
                            + "JOIN pg_stat_activity blocked ON blocked.pid = blocked_locks.pid "
                            + "JOIN pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype "
                            + "AND blocking_locks.relation = blocked_locks.relation "
                            + "AND blocking_locks.pid <> blocked_locks.pid "
                            + "AND blocking_locks.granted "
                            + "JOIN pg_stat_activity blocking ON blocking.pid = blocking_locks.pid "
                            + "LEFT JOIN pg_class c ON blocked_locks.relation = c.oid "
                            + "WHERE NOT blocked_locks.granted "
                            + "AND blocked.datname = ? "
                            + "ORDER BY waiting_seconds DESC "
                            + "LIMIT 20")) {
                stmt.setString(1, databaseName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        blockedProcesses.add(new BlockedProcess(
                                rs.getInt("blocked_pid"),
                                rs.getString("blocked_user"),
                                normalizeQuery(rs.getString("blocked_query")),
                                rs.getString("blocked_mode"),
                                rs.getString("relname"),
                                rs.getInt("blocking_pid"),
                                rs.getString("blocking_user"),
                                normalizeQuery(rs.getString("blocking_query")),
                                rs.getString("blocking_mode"),
                                rs.getLong("waiting_seconds")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.errorf("Failed to get database activity for '%s': %s", databaseName, e.getMessage());
        }

        // Top queries from pg_stat_statements (requires connecting to target DB)
        try {
            try (Connection dbConn = getTargetDbConnection(repository, databaseName)) {
                // Check if pg_stat_statements extension is available
                try (PreparedStatement check = dbConn.prepareStatement(
                        "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'");
                     ResultSet rs = check.executeQuery()) {
                    pgssAvailable = rs.next();
                }

                if (pgssAvailable) {
                    try (PreparedStatement stmt = dbConn.prepareStatement(
                            "SELECT query, calls, total_exec_time AS total_time_ms, "
                                    + "mean_exec_time AS mean_time_ms, rows, "
                                    + "COALESCE(temp_blks_read, 0) AS temp_blks_read, "
                                    + "COALESCE(temp_blks_written, 0) AS temp_blks_written "
                                    + "FROM pg_stat_statements "
                                    + "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                                    + "ORDER BY total_exec_time DESC LIMIT 10");
                         ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            topQueries.add(new TopQuery(
                                    normalizeQuery(rs.getString("query")),
                                    rs.getLong("calls"),
                                    rs.getDouble("total_time_ms"),
                                    rs.getDouble("mean_time_ms"),
                                    rs.getLong("rows"),
                                    rs.getLong("temp_blks_read"),
                                    rs.getLong("temp_blks_written")
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.debugf("Could not fetch pg_stat_statements for '%s': %s", databaseName, e.getMessage());
        }

        return new DatabaseActivity(sessions, users, topQueries, blockedProcesses, pgssAvailable);
    }

    public List<TopQuery> getTopQueriesForTable(String repository, String databaseName,
                                                 String tableName, int timeoutSeconds) {
        List<TopQuery> queries = new ArrayList<>();
        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'");
                 ResultSet rs = check.executeQuery()) {
                if (!rs.next()) return queries;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT query, calls, total_exec_time AS total_time_ms, "
                            + "mean_exec_time AS mean_time_ms, rows, "
                            + "COALESCE(temp_blks_read, 0) AS temp_blks_read, "
                            + "COALESCE(temp_blks_written, 0) AS temp_blks_written "
                            + "FROM pg_stat_statements "
                            + "WHERE query ILIKE '%' || ? || '%' "
                            + "ORDER BY total_exec_time DESC LIMIT 5")) {
                stmt.setQueryTimeout(timeoutSeconds);
                stmt.setString(1, tableName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        queries.add(new TopQuery(
                                normalizeQuery(rs.getString("query")),
                                rs.getLong("calls"),
                                rs.getDouble("total_time_ms"),
                                rs.getDouble("mean_time_ms"),
                                rs.getLong("rows"),
                                rs.getLong("temp_blks_read"),
                                rs.getLong("temp_blks_written")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.debugf("Could not fetch queries for table '%s': %s", tableName, e.getMessage());
        }
        return queries;
    }

    public List<TopQuery> getTopTempFileQueries(String repository, String databaseName, int timeoutSeconds) {
        List<TopQuery> queries = new ArrayList<>();
        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'");
                 ResultSet rs = check.executeQuery()) {
                if (!rs.next()) return queries;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT query, calls, total_exec_time AS total_time_ms, "
                            + "mean_exec_time AS mean_time_ms, rows, "
                            + "COALESCE(temp_blks_read, 0) AS temp_blks_read, "
                            + "COALESCE(temp_blks_written, 0) AS temp_blks_written "
                            + "FROM pg_stat_statements "
                            + "WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                            + "AND temp_blks_written > 0 "
                            + "ORDER BY temp_blks_written DESC LIMIT 20")) {
                stmt.setQueryTimeout(timeoutSeconds);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        queries.add(new TopQuery(
                                normalizeQuery(rs.getString("query")),
                                rs.getLong("calls"),
                                rs.getDouble("total_time_ms"),
                                rs.getDouble("mean_time_ms"),
                                rs.getLong("rows"),
                                rs.getLong("temp_blks_read"),
                                rs.getLong("temp_blks_written")
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.debugf("Could not fetch top temp file queries for '%s': %s", databaseName, e.getMessage());
        }
        return queries;
    }

    public record TableStats(
            String tableName,
            String schemaName,
            long totalSizeBytes,
            long tableSizeBytes,
            long indexSizeBytes,
            long liveTuples,
            long deadTuples,
            long seqScan,
            long idxScan,
            String lastVacuum,
            String lastAutoVacuum,
            String lastAnalyze,
            String lastAutoAnalyze
    ) {}

    public record IndexInfo(String indexName, String tableName, String schemaName,
                            long sizeBytes, long idxScan, boolean isPrimary, boolean isUnique) {}

    public record IndexImpact(String tableName, int unusedIndexes, long wastedBytes, long totalWrites, long seqScans, long idxScans) {}

    public record DatabaseTableStats(
            List<TableStats> tables,
            List<IndexInfo> unusedIndexes,
            List<IndexInfo> usedIndexes,
            List<IndexImpact> indexImpact,
            String statsResetAt
    ) {}

    public DatabaseTableStats getDatabaseTableStats(String repository, String databaseName) {
        List<TableStats> tables = new ArrayList<>();
        List<IndexInfo> unusedIndexes = new ArrayList<>();
        List<IndexInfo> usedIndexes = new ArrayList<>();
        List<IndexImpact> indexImpact = new ArrayList<>();
        String statsResetAt = null;

        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            // Stats reset timestamp — all pg_stat counters are relative to this point
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT stats_reset::text FROM pg_stat_database WHERE datname = current_database()");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) statsResetAt = rs.getString(1);
            }
            // Top tables by total size with stats
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT schemaname, relname, "
                            + "pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(relname)) AS total_size, "
                            + "pg_relation_size(quote_ident(schemaname) || '.' || quote_ident(relname)) AS table_size, "
                            + "pg_indexes_size(quote_ident(schemaname) || '.' || quote_ident(relname)) AS index_size, "
                            + "n_live_tup, n_dead_tup, seq_scan, idx_scan, "
                            + "last_vacuum::text, last_autovacuum::text, last_analyze::text, last_autoanalyze::text "
                            + "FROM pg_stat_user_tables "
                            + "ORDER BY total_size DESC "
                            + "LIMIT 200");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tables.add(new TableStats(
                            rs.getString("relname"),
                            rs.getString("schemaname"),
                            rs.getLong("total_size"),
                            rs.getLong("table_size"),
                            rs.getLong("index_size"),
                            rs.getLong("n_live_tup"),
                            rs.getLong("n_dead_tup"),
                            rs.getLong("seq_scan"),
                            rs.getLong("idx_scan"),
                            rs.getString("last_vacuum"),
                            rs.getString("last_autovacuum"),
                            rs.getString("last_analyze"),
                            rs.getString("last_autoanalyze")
                    ));
                }
            }

            // Unused indexes (idx_scan = 0, excluding primary keys and unique constraints)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.indexrelname AS index_name, s.relname AS table_name, s.schemaname, "
                            + "pg_relation_size(s.indexrelid) AS size_bytes, s.idx_scan, "
                            + "i.indisprimary, i.indisunique "
                            + "FROM pg_stat_user_indexes s "
                            + "JOIN pg_index i ON s.indexrelid = i.indexrelid "
                            + "WHERE s.idx_scan = 0 AND NOT i.indisprimary AND NOT i.indisunique "
                            + "ORDER BY size_bytes DESC "
                            + "LIMIT 200");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    unusedIndexes.add(new IndexInfo(
                            rs.getString("index_name"),
                            rs.getString("table_name"),
                            rs.getString("schemaname"),
                            rs.getLong("size_bytes"),
                            rs.getLong("idx_scan"),
                            rs.getBoolean("indisprimary"),
                            rs.getBoolean("indisunique")
                    ));
                }
            }

            // Used indexes (idx_scan > 0, ordered by most used)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.indexrelname AS index_name, s.relname AS table_name, s.schemaname, "
                            + "pg_relation_size(s.indexrelid) AS size_bytes, s.idx_scan, "
                            + "i.indisprimary, i.indisunique "
                            + "FROM pg_stat_user_indexes s "
                            + "JOIN pg_index i ON s.indexrelid = i.indexrelid "
                            + "WHERE s.idx_scan > 0 "
                            + "ORDER BY s.idx_scan DESC "
                            + "LIMIT 200");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    usedIndexes.add(new IndexInfo(
                            rs.getString("index_name"),
                            rs.getString("table_name"),
                            rs.getString("schemaname"),
                            rs.getLong("size_bytes"),
                            rs.getLong("idx_scan"),
                            rs.getBoolean("indisprimary"),
                            rs.getBoolean("indisunique")
                    ));
                }
            }
            // Index impact analysis: unused index overhead grouped by table
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT s.relname AS table_name, "
                            + "count(*) AS unused_indexes, "
                            + "sum(pg_relation_size(s.indexrelid)) AS wasted_bytes, "
                            + "(SELECT n_tup_ins + n_tup_upd + n_tup_del "
                            + "   FROM pg_stat_user_tables t "
                            + "   WHERE t.relname = s.relname) AS total_writes, "
                            + "(SELECT seq_scan "
                            + "   FROM pg_stat_user_tables t "
                            + "   WHERE t.relname = s.relname) AS seq_scans, "
                            + "(SELECT idx_scan "
                            + "   FROM pg_stat_user_tables t "
                            + "   WHERE t.relname = s.relname) AS idx_scans "
                            + "FROM pg_stat_user_indexes s "
                            + "JOIN pg_index i ON s.indexrelid = i.indexrelid "
                            + "WHERE s.idx_scan = 0 AND NOT i.indisprimary AND NOT i.indisunique "
                            + "GROUP BY s.relname "
                            + "ORDER BY wasted_bytes DESC");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    indexImpact.add(new IndexImpact(
                            rs.getString("table_name"),
                            rs.getInt("unused_indexes"),
                            rs.getLong("wasted_bytes"),
                            rs.getLong("total_writes"),
                            rs.getLong("seq_scans"),
                            rs.getLong("idx_scans")
                    ));
                }
            }
        } catch (Exception e) {
            Log.errorf("Failed to get table stats for '%s': %s", databaseName, e.getMessage());
        }

        return new DatabaseTableStats(tables, unusedIndexes, usedIndexes, indexImpact, statsResetAt);
    }

    public enum PgssResult { ENABLED, ALREADY_INSTALLED, NOT_AVAILABLE }

    public PgssResult enablePgStatStatements(String repository, String databaseName) {
        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            // Check if already installed
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return PgssResult.ALREADY_INSTALLED;
            }

            // Check if module is available (must be in shared_preload_libraries)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT 1 FROM pg_available_extensions WHERE name = 'pg_stat_statements'");
                 ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return PgssResult.NOT_AVAILABLE;
            }

            // Create extension
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            }
            Log.infof("pg_stat_statements extension enabled for database '%s' on repository '%s'.", databaseName, repository);
            return PgssResult.ENABLED;
        } catch (Exception e) {
            throw new RuntimeException("Failed to enable pg_stat_statements for '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    public enum QueryType { SELECT, WRITE, DDL }

    public record QueryResult(
            List<String> columns,
            List<List<Object>> rows,
            int page,
            int pageSize,
            long totalRows,
            long executionTimeMs,
            String queryType
    ) {}

    public QueryType detectQueryType(String sql) {
        // Strip leading comments before detecting type
        String cleaned = sql.strip().replaceAll("(?s)^(\\s*/\\*.*?\\*/\\s*|\\s*--[^\\n]*\\n\\s*)*", "");
        String upper = cleaned.strip().toUpperCase();
        if (upper.startsWith("SELECT") || upper.startsWith("EXPLAIN") || upper.startsWith("SHOW")) {
            return QueryType.SELECT;
        }
        if (upper.startsWith("WITH")) {
            // WITH can be CTE for SELECT or CTE for INSERT/UPDATE/DELETE
            return (upper.contains("INSERT") || upper.contains("UPDATE") || upper.contains("DELETE"))
                    ? QueryType.WRITE : QueryType.SELECT;
        }
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            return QueryType.WRITE;
        }
        return QueryType.DDL;
    }

    public String executeExplainJson(String repository, String databaseName, String sql,
                                     boolean analyze, int timeoutSeconds) {
        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            String prefix = analyze ? "EXPLAIN (FORMAT JSON, ANALYZE) " : "EXPLAIN (FORMAT JSON) ";
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(timeoutSeconds);
                try (ResultSet rs = stmt.executeQuery(prefix + sql)) {
                    if (rs.next()) return rs.getString(1);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return "[]";
    }

    public QueryResult executeQuery(String repository, String databaseName, String sql,
                                    int page, int pageSize, int timeoutSeconds,
                                    long cachedTotalRows) {
        long start = System.currentTimeMillis();
        QueryType type = detectQueryType(sql);

        try (Connection conn = getTargetDbConnection(repository, databaseName)) {
            // EXPLAIN queries cannot be wrapped in subqueries — execute directly
            boolean isExplain = sql.strip().toUpperCase().startsWith("EXPLAIN");
            if (type == QueryType.SELECT && isExplain) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSeconds);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        return buildResultFromResultSet(rs, 0, 9999, -1, start);
                    }
                }
            }

            if (type == QueryType.SELECT) {
                boolean useCached = cachedTotalRows >= 0;
                String pagedSql;
                if (useCached) {
                    // Total already known from a previous page — skip COUNT(*) OVER()
                    pagedSql = "SELECT * FROM (" + sql + ") AS _paged_q LIMIT " + pageSize + " OFFSET " + ((long) page * pageSize);
                } else {
                    // First page: compute total row count via window function
                    pagedSql = "SELECT *, COUNT(*) OVER() AS _total_rows FROM (" + sql + ") AS _paged_q LIMIT " + pageSize + " OFFSET " + ((long) page * pageSize);
                }

                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSeconds);
                    stmt.setFetchSize(pageSize);
                    try (ResultSet rs = stmt.executeQuery(pagedSql)) {
                        return useCached
                                ? buildResultFromResultSet(rs, page, pageSize, cachedTotalRows, start)
                                : buildResultFromResultSetWithTotal(rs, page, pageSize, start);
                    }
                }
            } else {
                // Write query: execute directly, return affected rows
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSeconds);
                    int affected = stmt.executeUpdate(sql);
                    long elapsed = System.currentTimeMillis() - start;
                    return new QueryResult(
                            List.of("affected_rows"),
                            List.of(List.of((Object) affected)),
                            0, 1, 1, elapsed, type.name()
                    );
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private QueryResult buildResultFromResultSetWithTotal(ResultSet rs, int page, int pageSize,
                                                          long startTime) throws Exception {
        var meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        // Last column is _total_rows, exclude from results
        int userColCount = colCount > 0 && "_total_rows".equals(meta.getColumnLabel(colCount)) ? colCount - 1 : colCount;

        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= userColCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        long totalRows = -1;
        while (rs.next()) {
            if (totalRows < 0 && userColCount < colCount) {
                totalRows = rs.getLong(colCount);
            }
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= userColCount; i++) {
                Object val = rs.getObject(i);
                if (val instanceof byte[]) {
                    val = "[binary " + ((byte[]) val).length + " bytes]";
                } else if (val != null) {
                    String str = String.valueOf(val);
                    if (str.length() > 1000) {
                        val = str.substring(0, 1000) + "... [truncated]";
                    } else {
                        val = str;
                    }
                }
                row.add(val);
            }
            rows.add(row);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new QueryResult(columns, rows, page, pageSize, totalRows, elapsed, "SELECT");
    }

    private QueryResult buildResultFromResultSet(ResultSet rs, int page, int pageSize,
                                                  long totalRows, long startTime) throws Exception {
        var meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                Object val = rs.getObject(i);
                if (val instanceof byte[]) {
                    val = "[binary " + ((byte[]) val).length + " bytes]";
                } else if (val != null) {
                    String str = String.valueOf(val);
                    if (str.length() > 1000) {
                        val = str.substring(0, 1000) + "... [truncated]";
                    } else {
                        val = str;
                    }
                }
                row.add(val);
            }
            rows.add(row);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new QueryResult(columns, rows, page, pageSize, totalRows, elapsed, "SELECT");
    }

    private Connection getTargetDbConnection(String repository, String databaseName) {
        String host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .orElseThrow(() -> new IllegalStateException("No PG host configured for repository: " + repository));
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        String user = config.getOptionalValue("repository.pg-user." + repository, String.class).orElse("postgres");
        String password = config.getOptionalValue("repository.pg-password." + repository, String.class).orElse("");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + databaseName + "?connectTimeout=5&socketTimeout=10";
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to database '" + databaseName + "': " + e.getMessage(), e);
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null) return "";
        return query.strip().replaceAll("\\s+", " ");
    }

    public record ServerHealth(
            String pgVersion,
            Instant serverStartedAt,
            int maxConnections,
            int totalConnections,
            long totalDiskSize
    ) {}

    public ServerHealth getServerHealth(String repository) {
        try (Connection conn = getConnection(repository)) {
            String pgVersion = null;
            Instant startedAt = null;
            int maxConn = 0;
            int totalConn = 0;
            long totalDisk = 0;

            try (PreparedStatement stmt = conn.prepareStatement("SELECT version()");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String full = rs.getString(1);
                    // Extract short version like "PostgreSQL 16.2" from the full string
                    int idx = full.indexOf(',');
                    pgVersion = idx > 0 ? full.substring(0, idx).trim() : full;
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_postmaster_start_time()");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts != null) startedAt = ts.toInstant();
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("SHOW max_connections");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) maxConn = Integer.parseInt(rs.getString(1));
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) totalConn = rs.getInt(1);
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT SUM(pg_database_size(datname)) FROM pg_database WHERE datistemplate = false");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) totalDisk = rs.getLong(1);
            }

            return new ServerHealth(pgVersion, startedAt, maxConn, totalConn, totalDisk);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get server health: " + e.getMessage(), e);
        }
    }

    public int getActiveConnectionCount(String repository, String databaseName) {
        try (Connection conn = getConnection(repository);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = ?")) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            Log.debugf("Failed to get connection count for '%s': %s", databaseName, e.getMessage());
        }
        return 0;
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

    // PgConnectionInfo record is now defined in DatabasePort interface

    @Override
    public PgConnectionInfo getConnectionInfo(String repository) {
        String host = config.getOptionalValue("repository.pg-host." + repository, String.class)
                .orElseThrow(() -> new IllegalStateException("No PG host configured for repository: " + repository));
        int port = config.getOptionalValue("repository.pg-port." + repository, Integer.class).orElse(5432);
        String user = config.getOptionalValue("repository.pg-user." + repository, String.class).orElse("postgres");
        String password = config.getOptionalValue("repository.pg-password." + repository, String.class).orElse("");
        return new PgConnectionInfo(host, port, user, password);
    }
}
