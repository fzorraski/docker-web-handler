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
    private String tenantId;
    /** Set when the caller deliberately wants an untenanted snapshot. */
    private boolean noTenant;
    private java.util.List<String> sharedWithTenants;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isTemporary() { return temporary; }
    public void setTemporary(boolean temporary) { this.temporary = temporary; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public boolean isNoTenant() { return noTenant; }
    public void setNoTenant(boolean noTenant) { this.noTenant = noTenant; }

    public java.util.List<String> getSharedWithTenants() { return sharedWithTenants; }
    public void setSharedWithTenants(java.util.List<String> sharedWithTenants) { this.sharedWithTenants = sharedWithTenants; }
}
