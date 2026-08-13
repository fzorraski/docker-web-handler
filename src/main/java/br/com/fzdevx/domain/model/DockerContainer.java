package br.com.fzdevx.domain.model;

import io.quarkus.logging.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DockerContainer {

    private String containerId;

    private String image;

    private String command;

    private String created;

    private String status;

    private String ports;

    private String names;

    private String expiresAt;

    private Map<String, String> portPaths;

    private String databaseName;

    private boolean deleteDatabaseOnExpiration;

    private String repository;

    private String ipAddress;

    private String createdBy;
    private String tenantId;
    /** Tenants the owner shared it with; they get the same access the owner has. */
    private List<String> sharedWithTenants = new ArrayList<>();

    private boolean upgradeEnabled;

    private boolean protectedFlag;

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPorts() {
        return ports;
    }

    public void setPorts(String ports) {
        if (ports.equals("-")) {
            this.ports = ports;
        } else {
            this.ports = extractUniquePublicPorts(ports)
                    .toString()
                    .replaceAll("[\\[\\]]", "");
        }
    }

    private Set<Integer> extractUniquePublicPorts(String inputString) {
        Set<Integer> uniquePublicPorts = new HashSet<>();
        Pattern pattern = Pattern.compile("publicPort=(\\d+)");
        Matcher matcher = pattern.matcher(inputString);
        while (matcher.find()) {
            String portStr = matcher.group(1);
            try {
                if (portStr != null && !portStr.isEmpty()) {
                    uniquePublicPorts.add(Integer.parseInt(portStr));
                }
            } catch (NumberFormatException e) {
                Log.warnf("Failed to parse port number: %s", portStr);
            }
        }
        return uniquePublicPorts;
    }


    public String getNames() {
        return names;
    }

    public void setNames(String names) {
        this.names = names;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, String> getPortPaths() {
        return portPaths;
    }

    public void setPortPaths(Map<String, String> portPaths) {
        this.portPaths = portPaths;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public boolean isDeleteDatabaseOnExpiration() {
        return deleteDatabaseOnExpiration;
    }

    public void setDeleteDatabaseOnExpiration(boolean deleteDatabaseOnExpiration) {
        this.deleteDatabaseOnExpiration = deleteDatabaseOnExpiration;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public List<String> getSharedWithTenants() {
        return sharedWithTenants;
    }

    public void setSharedWithTenants(List<String> sharedWithTenants) {
        this.sharedWithTenants = sharedWithTenants == null ? new ArrayList<>() : new ArrayList<>(sharedWithTenants);
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public boolean isUpgradeEnabled() {
        return upgradeEnabled;
    }

    public void setUpgradeEnabled(boolean upgradeEnabled) {
        this.upgradeEnabled = upgradeEnabled;
    }

    public boolean isProtectedFlag() {
        return protectedFlag;
    }

    public void setProtectedFlag(boolean protectedFlag) {
        this.protectedFlag = protectedFlag;
    }

    @Override
    public String toString() {
        return "Container{" +
                "containerId='" + containerId + '\'' +
                ", image='" + image + '\'' +
                ", command='" + command + '\'' +
                ", created='" + created + '\'' +
                ", status='" + status + '\'' +
                ", ports=" + ports +
                ", names='" + names + '\'' +
                '}';
    }
}
