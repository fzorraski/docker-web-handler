package br.com.fzdevx.application.dto;

public class CreateSnapshotRequest {

    private String repository;
    private String sourceDatabaseName;
    private String format;
    private String label;
    private String expiresAt;
    private String password;
    private String containerName;

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getSourceDatabaseName() { return sourceDatabaseName; }
    public void setSourceDatabaseName(String sourceDatabaseName) { this.sourceDatabaseName = sourceDatabaseName; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    private String description;
    private boolean temporary;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isTemporary() { return temporary; }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }
}
