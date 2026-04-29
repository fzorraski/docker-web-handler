package br.com.fzdevx.domain.shared;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class InputValidator {

    private static final Pattern REPOSITORY_PATTERN =
            Pattern.compile("^[a-z0-9]+([._/-][a-z0-9]+)*$");

    private static final Pattern TAG_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}$");

    private static final Pattern CONTAINER_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");

    private static final Pattern ENV_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final Pattern CONTAINER_ID_PATTERN =
            Pattern.compile("^[a-fA-F0-9]{10,64}$");

    private static final Pattern IMAGE_ID_PATTERN =
            Pattern.compile("^(sha256:)?[a-fA-F0-9]{7,64}$");

    private static final Pattern DATABASE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]{0,62}$");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}$");

    private static final List<String> ALLOWED_DUMP_EXTENSIONS =
            List.of(".sql", ".dump", ".gz", ".tar.gz");

    private InputValidator() {}

    public static Optional<String> validateRepository(String repository) {
        if (repository == null || repository.isBlank()) {
            return Optional.of("Repository name is required.");
        }
        if (repository.length() > 255) {
            return Optional.of("Repository name exceeds maximum length of 255 characters.");
        }
        if (!REPOSITORY_PATTERN.matcher(repository).matches()) {
            return Optional.of("Repository name contains invalid characters. "
                    + "Only lowercase letters, digits, hyphens, underscores, dots, and slashes are allowed.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.of("Tag is required.");
        }
        if (tag.length() > 128) {
            return Optional.of("Tag exceeds maximum length of 128 characters.");
        }
        if (!TAG_PATTERN.matcher(tag).matches()) {
            return Optional.of("Tag contains invalid characters. "
                    + "Only letters, digits, hyphens, underscores, and dots are allowed.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateContainerName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (name.length() > 255) {
            return Optional.of("Container name exceeds maximum length of 255 characters.");
        }
        if (!CONTAINER_NAME_PATTERN.matcher(name).matches()) {
            return Optional.of("Container name contains invalid characters. "
                    + "Must start with a letter or digit, followed by letters, digits, hyphens, underscores, or dots.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateEnvVars(List<String> envVars) {
        if (envVars == null || envVars.isEmpty()) {
            return Optional.empty();
        }
        for (String envVar : envVars) {
            int eq = envVar.indexOf('=');
            if (eq <= 0) {
                return Optional.of("Invalid environment variable format: '" + envVar + "'. Expected KEY=VALUE.");
            }
            String key = envVar.substring(0, eq);
            if (!ENV_KEY_PATTERN.matcher(key).matches()) {
                return Optional.of("Invalid environment variable key: '" + key
                        + "'. Only letters, digits, and underscores are allowed, starting with a letter or underscore.");
            }
        }
        return Optional.empty();
    }

    public static Optional<String> validateMemoryMb(Long memoryMb) {
        if (memoryMb == null) {
            return Optional.empty();
        }
        if (memoryMb < 4) {
            return Optional.of("Memory must be at least 4 MB.");
        }
        if (memoryMb > 65536) {
            return Optional.of("Memory must not exceed 65536 MB (64 GB).");
        }
        return Optional.empty();
    }

    public static Optional<String> validateContainerId(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return Optional.of("Container ID is required.");
        }
        if (!CONTAINER_ID_PATTERN.matcher(containerId).matches()) {
            return Optional.of("Invalid container ID format.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateDatabaseName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of("Database name is required.");
        }
        if (!DATABASE_NAME_PATTERN.matcher(name).matches()) {
            return Optional.of("Invalid database name. "
                    + "Must start with a letter or underscore, followed by up to 62 letters, digits, underscores, or hyphens.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateImageId(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return Optional.of("Image ID is required.");
        }
        if (!IMAGE_ID_PATTERN.matcher(imageId).matches()) {
            return Optional.of("Invalid image ID format.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateTableOrSchemaName(String name, String label) {
        if (name == null || name.isBlank()) {
            return Optional.of(label + " is required.");
        }
        if (!DATABASE_NAME_PATTERN.matcher(name).matches()) {
            return Optional.of("Invalid " + label.toLowerCase()
                    + ". Must start with a letter or underscore, followed by up to 62 letters, digits, underscores, or hyphens.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.of("Filename is required.");
        }
        if (filename.contains("..")) {
            return Optional.of("Filename must not contain '..'.");
        }
        if (!FILENAME_PATTERN.matcher(filename).matches()) {
            return Optional.of("Filename contains invalid characters.");
        }
        String lower = filename.toLowerCase();
        boolean valid = ALLOWED_DUMP_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!valid) {
            return Optional.of("Unsupported file format. Allowed: .sql, .dump, .gz, .tar.gz");
        }
        return Optional.empty();
    }

    public static Optional<String> validateUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return Optional.of("UUID is required.");
        }
        if (!UUID_PATTERN.matcher(uuid).matches()) {
            return Optional.of("Invalid UUID format.");
        }
        return Optional.empty();
    }

    private static final Pattern SNAPSHOT_LABEL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,99}$");

    public static Optional<String> validateSnapshotFormat(String format) {
        if (format == null || format.isBlank()) {
            return Optional.of("Snapshot format is required.");
        }
        if (!"CUSTOM".equals(format) && !"SQL".equals(format)) {
            return Optional.of("Snapshot format must be 'CUSTOM' or 'SQL'.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateSnapshotLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        if (label.length() > 100) {
            return Optional.of("Snapshot label exceeds maximum length of 100 characters.");
        }
        if (!SNAPSHOT_LABEL_PATTERN.matcher(label).matches()) {
            return Optional.of("Snapshot label contains invalid characters. "
                    + "Only letters, digits, spaces, hyphens, underscores, and dots are allowed.");
        }
        return Optional.empty();
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");

    public static Optional<String> validateMigrationMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return Optional.empty();
        }
        if (!"MANUAL".equals(mode) && !"API".equals(mode)) {
            return Optional.of("Migration mode must be 'MANUAL' or 'API'.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateVersion(String version) {
        if (version == null || version.isBlank()) {
            return Optional.of("Version is required.");
        }
        if (version.length() > 128) {
            return Optional.of("Version exceeds maximum length of 128 characters.");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            return Optional.of("Version contains invalid characters. "
                    + "Only letters, digits, hyphens, underscores, and dots are allowed.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateMigrationSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.of("Migration SQL is required.");
        }
        if (sql.length() > 10_000_000) {
            return Optional.of("Migration SQL exceeds maximum size of 10 MB.");
        }
        return Optional.empty();
    }

    private static final Pattern SCHEDULE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,99}$");

    public static Optional<String> validateScheduleName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of("Schedule name is required.");
        }
        if (name.length() > 100) {
            return Optional.of("Schedule name exceeds maximum length of 100 characters.");
        }
        if (!SCHEDULE_NAME_PATTERN.matcher(name).matches()) {
            return Optional.of("Schedule name contains invalid characters. "
                    + "Only letters, digits, spaces, hyphens, underscores, and dots are allowed.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateCronExpression(String cron) {
        if (cron == null || cron.isBlank()) {
            return Optional.of("Cron expression is required.");
        }
        if (!CronParser.isValid(cron)) {
            return Optional.of("Invalid cron expression. Expected 5 fields: minute hour day-of-month month day-of-week.");
        }
        return Optional.empty();
    }

    private static final Pattern CONTAINER_PATH_PATTERN =
            Pattern.compile("^/[a-zA-Z0-9/_.-]+$");

    public static Optional<String> validateContainerPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.of("Destination path is required.");
        }
        if (!path.startsWith("/")) {
            return Optional.of("Destination path must be absolute (start with '/').");
        }
        if (path.contains("..")) {
            return Optional.of("Destination path must not contain '..'.");
        }
        if (path.length() > 4096) {
            return Optional.of("Destination path exceeds maximum length of 4096 characters.");
        }
        if (!CONTAINER_PATH_PATTERN.matcher(path).matches()) {
            return Optional.of("Destination path contains invalid characters. "
                    + "Only letters, digits, slashes, hyphens, underscores, and dots are allowed.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateUploadFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.of("Filename is required.");
        }
        if (filename.contains("/") || filename.contains("\\")) {
            return Optional.of("Filename must not contain path separators.");
        }
        if (filename.contains("..")) {
            return Optional.of("Filename must not contain '..'.");
        }
        if (filename.length() > 255) {
            return Optional.of("Filename exceeds maximum length of 255 characters.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateScriptFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.of("Script filename is required.");
        }
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return Optional.of("Script filename must not contain path separators or '..'.");
        }
        if (!filename.toLowerCase().endsWith(".sql")) {
            return Optional.of("Script filename must end with '.sql'.");
        }
        if (!FILENAME_PATTERN.matcher(filename).matches()) {
            return Optional.of("Script filename contains invalid characters.");
        }
        return Optional.empty();
    }
}
