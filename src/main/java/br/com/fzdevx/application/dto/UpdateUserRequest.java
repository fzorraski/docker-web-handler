package br.com.fzdevx.application.dto;

import java.util.List;

public class UpdateUserRequest {

    private List<String> roleIds;
    private Boolean enabled;

    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
