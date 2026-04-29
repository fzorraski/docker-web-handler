package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.ManagedDatabaseInfo;
import br.com.fzdevx.application.port.ManagedDatabaseRepository;
import br.com.fzdevx.application.usecase.CleanupIdleDatabasesUseCase;
import br.com.fzdevx.application.usecase.GenerateDatabaseReportUseCase;
import br.com.fzdevx.application.usecase.ListManagedDatabasesUseCase;
import br.com.fzdevx.domain.model.ContainerExpiration;
import br.com.fzdevx.infrastructure.docker.ContainerExpirationService;
import br.com.fzdevx.domain.model.ManagedDatabase;
import br.com.fzdevx.domain.shared.InputValidator;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import br.com.fzdevx.infrastructure.persistence.ResourceCounterService;
import br.com.fzdevx.infrastructure.persistence.DatabaseService;
import br.com.fzdevx.interfaces.rest.util.ContentDispositionHelper;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Path("/database/managed")
public class ManagedDatabaseController {

    @Inject
    @ConfigProperty(name = "database.managed.enabled", defaultValue = "false")
    boolean managedEnabled;

    @Inject
    @ConfigProperty(name = "database.query.enabled", defaultValue = "false")
    boolean queryEnabled;

    @Inject
    @ConfigProperty(name = "database.query.write-enabled", defaultValue = "false")
    boolean queryWriteEnabled;

    @Inject
    @ConfigProperty(name = "database.query.timeout-seconds", defaultValue = "30")
    int queryTimeoutSeconds;

    @Inject
    @ConfigProperty(name = "database.query.max-page-size", defaultValue = "500")
    int queryMaxPageSize;

    @Inject
    @ConfigProperty(name = "database.query.cache-total-rows", defaultValue = "true")
    boolean queryCacheTotalRows;

    @Inject
    @ConfigProperty(name = "database.query-stats.reset-enabled", defaultValue = "false")
    boolean queryStatsResetEnabled;

    @Inject
    ListManagedDatabasesUseCase listManagedDatabasesUseCase;

    @Inject
    GenerateDatabaseReportUseCase generateDatabaseReportUseCase;

    @Inject
    CleanupIdleDatabasesUseCase cleanupIdleDatabasesUseCase;

    @Inject
    ManagedDatabaseRepository managedDatabaseRepository;

    @Inject
    DatabaseService databaseService;

    @Inject
    PasswordValidationService passwordValidationService;

    @Inject
    ContainerExpirationService expirationService;

    @Inject
    ResourceCounterService resourceCounterService;

    @GET
    @Path("/enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean isEnabled() {
        return managedEnabled;
    }

    @GET
    @Path("/repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getRepositories() {
        if (!managedEnabled) {
            return Collections.emptyList();
        }
        return listManagedDatabasesUseCase.getRepositories();
    }

    @GET
    @Path("/activity/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDatabaseActivity(@PathParam("repository") String repository,
                                        @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        var activity = databaseService.getDatabaseActivity(repository, databaseName);
        return Response.ok(activity).build();
    }

    @GET
    @Path("/queries/{repository}/{databaseName}/{tableName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTopQueriesForTable(@PathParam("repository") String repository,
                                          @PathParam("databaseName") String databaseName,
                                          @PathParam("tableName") String tableName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }
        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }
        if (tableName == null || tableName.isBlank() || tableName.length() > 63) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid table name.")).build();
        }
        var queries = databaseService.getTopQueriesForTable(repository, databaseName, tableName, queryTimeoutSeconds);
        return Response.ok(queries).build();
    }

    @GET
    @Path("/temp-queries/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTopTempFileQueries(@PathParam("repository") String repository,
                                          @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }
        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }
        var queries = databaseService.getTopTempFileQueries(repository, databaseName, queryTimeoutSeconds);
        return Response.ok(queries).build();
    }

    @GET
    @Path("/details/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDatabaseDetails(@PathParam("repository") String repository,
                                       @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        Object health = null;
        Object activity = null;
        Object tableStats = null;

        try {
            health = databaseService.getDatabaseHealth(repository, databaseName);
        } catch (Exception e) {
            Log.warnf("Failed to get health for '%s': %s", databaseName, e.getMessage());
        }

        try {
            activity = databaseService.getDatabaseActivity(repository, databaseName);
        } catch (Exception e) {
            Log.warnf("Failed to get activity for '%s': %s", databaseName, e.getMessage());
        }

        try {
            tableStats = databaseService.getDatabaseTableStats(repository, databaseName);
        } catch (Exception e) {
            Log.warnf("Failed to get table stats for '%s': %s", databaseName, e.getMessage());
        }

        Map<String, Object> result = new java.util.HashMap<>();
        if (health != null) result.put("health", health);
        if (activity != null) result.put("activity", activity);
        if (tableStats != null) result.put("tableStats", tableStats);

        return Response.ok(result).build();
    }

    @GET
    @Path("/{repository}/{databaseName}/report")
    @Produces("text/html")
    public Response downloadDatabaseReport(@PathParam("repository") String repository,
                                           @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        String html = generateDatabaseReportUseCase.generateReport(repository, databaseName);
        String filename = generateDatabaseReportUseCase.buildReportFilename(repository, databaseName);
        return Response.ok(html, "text/html")
                .header("Content-Disposition", ContentDispositionHelper.buildAttachmentHeader(filename))
                .build();
    }

    @GET
    @Path("/tables/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDatabaseTableStats(@PathParam("repository") String repository,
                                          @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        var stats = databaseService.getDatabaseTableStats(repository, databaseName);
        return Response.ok(stats).build();
    }

    @POST
    @Path("/{repository}/{databaseName}/enable-pgss")
    @Produces(MediaType.APPLICATION_JSON)
    public Response enablePgStatStatements(@PathParam("repository") String repository,
                                           @PathParam("databaseName") String databaseName,
                                           @HeaderParam("X-Dump-Password") String password) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        var result = databaseService.enablePgStatStatements(repository, databaseName);
        return switch (result) {
            case ENABLED -> Response.ok(Map.of("success", true)).build();
            case ALREADY_INSTALLED -> Response.ok(Map.of("success", true, "alreadyInstalled", true)).build();
            case NOT_AVAILABLE -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "pg_stat_statements module is not available. Add it to shared_preload_libraries in postgresql.conf and restart the server.")).build();
        };
    }

    @POST
    @Path("/{repository}/{databaseName}/reset-query-stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response resetQueryStats(@PathParam("repository") String repository,
                                    @PathParam("databaseName") String databaseName,
                                    @HeaderParam("X-Dump-Password") String password) {
        if (!managedEnabled || !queryStatsResetEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        try {
            databaseService.resetQueryStats(repository, databaseName);
            return Response.ok(Map.of("success", true)).build();
        } catch (Exception e) {
            Log.warnf("Failed to reset query stats for '%s': %s", databaseName, e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : "Failed to reset query stats.";
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", msg)).build();
        }
    }

    @GET
    @Path("/query-enabled")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> isQueryEnabled() {
        return Map.of("enabled", queryEnabled, "writeEnabled", queryWriteEnabled, "queryStatsResetEnabled", queryStatsResetEnabled);
    }

    @POST
    @Path("/{repository}/{databaseName}/explain")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response explainQuery(@PathParam("repository") String repository,
                                 @PathParam("databaseName") String databaseName,
                                 Map<String, Object> body) {
        if (!managedEnabled || !queryEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        String sql = body.get("sql") != null ? body.get("sql").toString().strip() : "";
        sql = sql.replaceAll(";\\s*$", "");
        if (sql.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "SQL query is required.")).build();
        }

        boolean analyze = body.get("analyze") instanceof Boolean b && b;

        try {
            String jsonPlan = databaseService.executeExplainJson(repository, databaseName, sql, analyze, queryTimeoutSeconds);
            // Include table stats to avoid a second connection
            Object tableStats = null;
            try {
                tableStats = databaseService.getDatabaseTableStats(repository, databaseName);
            } catch (Exception ignored) {}
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("plan", jsonPlan);
            if (tableStats != null) result.put("tableStats", tableStats);
            return Response.ok(result).build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Explain failed.";
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", msg)).build();
        }
    }

    @POST
    @Path("/{repository}/{databaseName}/query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeQuery(@PathParam("repository") String repository,
                                 @PathParam("databaseName") String databaseName,
                                 @HeaderParam("X-Dump-Password") String password,
                                 Map<String, Object> body) {
        if (!managedEnabled || !queryEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        String sql = body.get("sql") != null ? body.get("sql").toString().strip() : "";
        if (sql.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "SQL query is required.")).build();
        }
        if (sql.length() > 102400) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "SQL query exceeds maximum size of 100KB.")).build();
        }
        // Strip trailing semicolon, then check for remaining ones (multi-statement)
        sql = sql.replaceAll(";\\s*$", "");
        if (sql.contains(";")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Multiple statements are not allowed.")).build();
        }

        DatabaseService.QueryType queryType = databaseService.detectQueryType(sql);

        if (queryType == DatabaseService.QueryType.DDL) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "DDL statements (CREATE, ALTER, DROP, TRUNCATE, GRANT, REVOKE) are not allowed.")).build();
        }

        if (queryType == DatabaseService.QueryType.WRITE) {
            if (!queryWriteEnabled) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Write queries are disabled. Enable database.query.write-enabled to allow INSERT/UPDATE/DELETE.")).build();
            }
            if (!passwordValidationService.validateOperationsPassword(password)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("error", "Operations password is required for write queries.")).build();
            }
        }

        int page = 0;
        int pageSize = 100;
        long cachedTotalRows = -1;
        try {
            if (body.get("page") instanceof Number n) page = Math.max(0, n.intValue());
            if (body.get("pageSize") instanceof Number n) pageSize = Math.min(Math.max(1, n.intValue()), queryMaxPageSize);
            if (queryCacheTotalRows && body.get("totalRows") instanceof Number n && n.longValue() >= 0) {
                cachedTotalRows = n.longValue();
            }
        } catch (Exception ignored) {}

        try {
            var result = databaseService.executeQuery(repository, databaseName, sql, page, pageSize, queryTimeoutSeconds, cachedTotalRows);
            return Response.ok(result).build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Query execution failed.";
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", msg)).build();
        }
    }

    @GET
    @Path("/health/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDatabaseHealth(@PathParam("repository") String repository,
                                      @PathParam("databaseName") String databaseName) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        var health = databaseService.getDatabaseHealth(repository, databaseName);
        return Response.ok(health).build();
    }

    @GET
    @Path("/health/{repository}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getServerHealth(@PathParam("repository") String repository) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        if (!databaseService.hasDatabaseConfig(repository)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No database configuration for repository: " + repository)).build();
        }

        var health = databaseService.getServerHealth(repository);
        return Response.ok(health).build();
    }

    @GET
    @Path("/list/{repository}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listDatabases(@PathParam("repository") String repository) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        if (!databaseService.hasDatabaseConfig(repository)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No database configuration for repository: " + repository)).build();
        }

        try {
            List<ManagedDatabaseInfo> databases = listManagedDatabasesUseCase.listDatabases(repository);
            return Response.ok(databases).build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            boolean isConnectionError = msg.contains("connection") || msg.contains("connect")
                    || msg.contains("Connection") || msg.contains("timed out");
            Log.errorf("Failed to list databases for repository '%s': %s", repository, msg);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of(
                            "error", isConnectionError
                                    ? "Unable to connect to PostgreSQL for repository '" + repository + "'. Please check if the database server is running and accessible."
                                    : "Failed to list databases: " + msg,
                            "connectionError", isConnectionError
                    )).build();
        }
    }

    @DELETE
    @Path("/{repository}/{databaseName}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteDatabase(@PathParam("repository") String repository,
                                   @PathParam("databaseName") String databaseName,
                                   @HeaderParam("X-Dump-Password") String password,
                                   @QueryParam("force") @DefaultValue("false") boolean force) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        Optional<ManagedDatabase> md = managedDatabaseRepository.find(repository, databaseName);
        if (md.isPresent() && md.get().isProtectedFlag()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("errorCode", "DATABASE_PROTECTED")).build();
        }

        // Check if any containers are using this database
        List<ContainerExpiration> usedBy = expirationService.findByDatabaseName(databaseName);
        if (!usedBy.isEmpty()) {
            List<String> containerIds = usedBy.stream().map(ContainerExpiration::getShortId).toList();
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                            "errorCode", "DATABASE_IN_USE",
                            "count", usedBy.size(),
                            "inUseByContainers", containerIds
                    )).build();
        }

        // Check active connections (warn, don't block — user can force)
        if (!force) {
            try {
                int active = databaseService.getActiveConnectionCount(repository, databaseName);
                if (active > 0) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of(
                                    "errorCode", "ACTIVE_CONNECTIONS",
                                    "activeConnections", active,
                                    "requiresForce", true
                            )).build();
                }
            } catch (Exception e) {
                Log.debugf("Could not check active connections for '%s': %s", databaseName, e.getMessage());
            }
        }

        databaseService.dropDatabase(repository, databaseName);
        managedDatabaseRepository.delete(repository, databaseName);
        listManagedDatabasesUseCase.invalidateCache(repository);
        resourceCounterService.increment(ResourceCounterService.DATABASES_DELETED);
        return Response.ok(Map.of("success", true)).build();
    }

    @DELETE
    @Path("/{repository}/bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBulk(@PathParam("repository") String repository,
                               @HeaderParam("X-Dump-Password") String password,
                               List<String> names) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (names == null || names.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No database names provided.")).build();
        }

        if (names.size() > 100) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot delete more than 100 databases at once.")).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        Map<String, Integer> activeConnections = null; // lazy-loaded on first valid name

        int deleted = 0;
        int skipped = 0;
        for (String name : names) {
            if (InputValidator.validateDatabaseName(name).isPresent()) {
                skipped++;
                continue;
            }

            Optional<ManagedDatabase> md = managedDatabaseRepository.find(repository, name);
            if (md.isPresent() && md.get().isProtectedFlag()) {
                skipped++;
                continue;
            }

            // Skip databases in use by containers
            if (!expirationService.findByDatabaseName(name).isEmpty()) {
                skipped++;
                continue;
            }

            // Skip databases with active connections (lazy-load once)
            if (activeConnections == null) {
                try {
                    activeConnections = databaseService.getActiveConnectionCounts(repository);
                } catch (Exception e) {
                    activeConnections = Map.of();
                }
            }
            if (activeConnections.getOrDefault(name, 0) > 0) {
                skipped++;
                continue;
            }

            try {
                databaseService.dropDatabase(repository, name);
                managedDatabaseRepository.delete(repository, name);
                resourceCounterService.increment(ResourceCounterService.DATABASES_DELETED);
                deleted++;
            } catch (Exception e) {
                Log.errorf("Bulk delete: failed to drop database '%s': %s", name, e.getMessage());
                skipped++;
            }
        }

        listManagedDatabasesUseCase.invalidateCache(repository);
        return Response.ok(Map.of("success", true, "deleted", deleted, "skipped", skipped)).build();
    }

    @PUT
    @Path("/{repository}/{databaseName}/description")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateDescription(@PathParam("repository") String repository,
                                      @PathParam("databaseName") String databaseName,
                                      @HeaderParam("X-Dump-Password") String password,
                                      Map<String, String> body) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        String description = body.get("description");
        if (description != null && description.length() > 500) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Description exceeds maximum length of 500 characters.")).build();
        }
        ManagedDatabase md = managedDatabaseRepository.find(repository, databaseName)
                .orElseGet(() -> new ManagedDatabase(repository, databaseName));
        md.setDescription(description);
        managedDatabaseRepository.save(md);
        listManagedDatabasesUseCase.invalidateCache(repository);

        return Response.ok(Map.of("success", true)).build();
    }

    @PUT
    @Path("/{repository}/{databaseName}/protected")
    @Produces(MediaType.APPLICATION_JSON)
    public Response toggleProtected(@PathParam("repository") String repository,
                                    @PathParam("databaseName") String databaseName,
                                    @HeaderParam("X-Dump-Password") String password) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        Optional<String> nameError = InputValidator.validateDatabaseName(databaseName);
        if (nameError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", nameError.get())).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        ManagedDatabase md = managedDatabaseRepository.find(repository, databaseName)
                .orElseGet(() -> new ManagedDatabase(repository, databaseName));

        md.setProtectedFlag(!md.isProtectedFlag());
        managedDatabaseRepository.save(md);
        listManagedDatabasesUseCase.invalidateCache(repository);

        // Auto-disable database deletion on containers when protecting
        int disabledDeletionCount = 0;
        if (md.isProtectedFlag()) {
            List<ContainerExpiration> expirations = expirationService.findByDatabaseName(databaseName);
            for (ContainerExpiration exp : expirations) {
                if (exp.isDeleteDatabaseOnExpiration()) {
                    expirationService.disableDatabaseDeletion(exp.getShortId());
                    disabledDeletionCount++;
                }
            }
            if (disabledDeletionCount > 0) {
                Log.infof("Auto-disabled database deletion on %d container(s) for protected database '%s'.",
                        disabledDeletionCount, databaseName);
            }
        }

        return Response.ok(Map.of("success", true, "protected", md.isProtectedFlag(),
                "disabledDeletionCount", disabledDeletionCount)).build();
    }

    @POST
    @Path("/{repository}/cleanup-idle")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cleanupIdle(@PathParam("repository") String repository,
                                Map<String, Object> body) {
        if (!managedEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Optional<String> repoError = InputValidator.validateRepository(repository);
        if (repoError.isPresent()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", repoError.get())).build();
        }

        String password = body.get("password") != null ? body.get("password").toString() : "";
        int minDays;
        try {
            Object val = body.get("minDays");
            minDays = val instanceof Number ? ((Number) val).intValue() : 0;
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid minDays parameter.")).build();
        }

        if (!passwordValidationService.validateOperationsPassword(password)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Invalid operations password.")).build();
        }

        if (minDays < 1 || minDays > 365) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "minDays must be between 1 and 365.")).build();
        }

        int deleted = cleanupIdleDatabasesUseCase.cleanup(repository, minDays);
        listManagedDatabasesUseCase.invalidateCache(repository);
        return Response.ok(Map.of("success", true, "deleted", deleted)).build();
    }
}
