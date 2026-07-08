package br.com.fzdevx.application.dto;

import java.util.List;

public class CreateUserRequest {

    private String username;
    private String password;
    private List<String> roleIds;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getRoleIds() { return roleIds; }
    public void setRoleIds(List<String> roleIds) { this.roleIds = roleIds; }
}
