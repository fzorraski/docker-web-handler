package br.com.fzdevx.model;

import java.time.Instant;
import java.util.Objects;

public class ContainerExpiration {

    private String shortId;
    private String fullContainerId;
    private Instant expiresAt;

    public ContainerExpiration() {
    }

    public ContainerExpiration(String shortId, String fullContainerId, Instant expiresAt) {
        this.shortId = Objects.requireNonNull(shortId);
        this.fullContainerId = Objects.requireNonNull(fullContainerId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getShortId() {
        return shortId;
    }

    public void setShortId(String shortId) {
        this.shortId = shortId;
    }

    public String getFullContainerId() {
        return fullContainerId;
    }

    public void setFullContainerId(String fullContainerId) {
        this.fullContainerId = fullContainerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContainerExpiration that = (ContainerExpiration) o;
        return Objects.equals(shortId, that.shortId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortId);
    }
}
