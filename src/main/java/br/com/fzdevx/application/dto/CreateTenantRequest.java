package br.com.fzdevx.application.dto;

import java.util.List;

public class CreateTenantRequest {

    private String name;
    private String description;
    /** null = all allowed repositories enabled for the tenant. */
    private List<String> enabledRepositories;
    /** null = all configured database connections enabled for the tenant. */
    private List<String> enabledDatabases;
    /** Badge colour as #RRGGBB; null keeps the current one (or picks one on create). */
    private String color;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getEnabledRepositories() { return enabledRepositories; }
    public void setEnabledRepositories(List<String> enabledRepositories) { this.enabledRepositories = enabledRepositories; }

    public List<String> getEnabledDatabases() { return enabledDatabases; }
    public void setEnabledDatabases(List<String> enabledDatabases) { this.enabledDatabases = enabledDatabases; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
}
