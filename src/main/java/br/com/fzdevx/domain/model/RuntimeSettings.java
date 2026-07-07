package br.com.fzdevx.domain.model;

/**
 * Runtime-editable settings overrides. A null field means "no override" -
 * the value from application.properties (or its env var) applies. Persisted
 * as a single JSON object (data/settings.json) and editable at runtime by
 * users holding the SYSTEM_CONFIG permission.
 */
public class RuntimeSettings {

    private Boolean terminalEnabled;
    private Integer terminalMaxSessions;
    private Integer terminalIdleTimeoutMinutes;
    private Boolean terminalUploadEnabled;
    private Integer terminalUploadMaxSizeMb;
    private Boolean logAnalyzerEnabled;
    private Integer sessionTimeoutMinutes;

    public Boolean getTerminalEnabled() { return terminalEnabled; }
    public void setTerminalEnabled(Boolean terminalEnabled) { this.terminalEnabled = terminalEnabled; }

    public Integer getTerminalMaxSessions() { return terminalMaxSessions; }
    public void setTerminalMaxSessions(Integer terminalMaxSessions) { this.terminalMaxSessions = terminalMaxSessions; }

    public Integer getTerminalIdleTimeoutMinutes() { return terminalIdleTimeoutMinutes; }
    public void setTerminalIdleTimeoutMinutes(Integer terminalIdleTimeoutMinutes) { this.terminalIdleTimeoutMinutes = terminalIdleTimeoutMinutes; }

    public Boolean getTerminalUploadEnabled() { return terminalUploadEnabled; }
    public void setTerminalUploadEnabled(Boolean terminalUploadEnabled) { this.terminalUploadEnabled = terminalUploadEnabled; }

    public Integer getTerminalUploadMaxSizeMb() { return terminalUploadMaxSizeMb; }
    public void setTerminalUploadMaxSizeMb(Integer terminalUploadMaxSizeMb) { this.terminalUploadMaxSizeMb = terminalUploadMaxSizeMb; }

    public Boolean getLogAnalyzerEnabled() { return logAnalyzerEnabled; }
    public void setLogAnalyzerEnabled(Boolean logAnalyzerEnabled) { this.logAnalyzerEnabled = logAnalyzerEnabled; }

    public Integer getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) { this.sessionTimeoutMinutes = sessionTimeoutMinutes; }
}
