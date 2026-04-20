package br.com.fzdevx.domain.model;

import java.util.List;

public class DatabaseConflict {

    private String scheduledForDeletionBy;
    private List<String> inUseByContainers;
    private String expiresAt;
    private boolean protectedFlag;

    public DatabaseConflict() {
    }

    public DatabaseConflict(String scheduledForDeletionBy, List<String> inUseByContainers, String expiresAt) {
        this.scheduledForDeletionBy = scheduledForDeletionBy;
        this.inUseByContainers = inUseByContainers;
        this.expiresAt = expiresAt;
    }

    public String getScheduledForDeletionBy() {
        return scheduledForDeletionBy;
    }

    public void setScheduledForDeletionBy(String scheduledForDeletionBy) {
        this.scheduledForDeletionBy = scheduledForDeletionBy;
    }

    public List<String> getInUseByContainers() {
        return inUseByContainers;
    }

    public void setInUseByContainers(List<String> inUseByContainers) {
        this.inUseByContainers = inUseByContainers;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isProtectedFlag() {
        return protectedFlag;
    }

    public void setProtectedFlag(boolean protectedFlag) {
        this.protectedFlag = protectedFlag;
    }
}
