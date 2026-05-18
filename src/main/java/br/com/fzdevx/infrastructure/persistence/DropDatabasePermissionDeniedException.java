package br.com.fzdevx.infrastructure.persistence;

public class DropDatabasePermissionDeniedException extends RuntimeException {

    private final String databaseName;

    public DropDatabasePermissionDeniedException(String databaseName, Throwable cause) {
        super("Permission denied: the database user is not the owner of '" + databaseName + "'.", cause);
        this.databaseName = databaseName;
    }

    public String getDatabaseName() {
        return databaseName;
    }
}
