package br.com.fzdevx.application.dto;

import java.util.List;

public class UpdateUserRequest {

    private List<String> roleIds;
    private List<String> tenantIds;
    private Boolean enabled;

    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }

    public List<String> getTenantIds() { return tenantIds; }
    public void setTenantIds(List<String> tenantIds) { this.tenantIds = tenantIds; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
