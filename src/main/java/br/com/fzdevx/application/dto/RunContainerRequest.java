package br.com.fzdevx.application.dto;

import br.com.fzdevx.domain.model.RunContainerConfig;

/**
 * Extends the domain RunContainerConfig for use as a JAX-RS request DTO.
 * Inherits all container creation fields.
 */
public class RunContainerRequest extends RunContainerConfig {

    /**
     * Set when the caller deliberately wants an untenanted container. Request-only
     * on purpose: it is consumed while resolving the owner, and a schedule's
     * persisted config carries the resolved tenantId instead.
     */
    private boolean noTenant;

    public boolean isNoTenant() { return noTenant; }
    public void setNoTenant(boolean noTenant) { this.noTenant = noTenant; }
}
