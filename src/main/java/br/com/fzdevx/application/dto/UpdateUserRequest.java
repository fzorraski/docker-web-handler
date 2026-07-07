package br.com.fzdevx.application.dto;

public class UpdateUserRequest {

    private String roleId;
    private Boolean enabled;

    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
