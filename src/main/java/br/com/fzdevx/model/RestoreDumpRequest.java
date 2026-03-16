package br.com.fzdevx.model;

import java.util.List;

public class RestoreDumpRequest {

    private String dumpId;
    private String repository;
    private String targetDatabase;
    private boolean createDatabase;
    private String password;
    private List<String> selectedOptionalScripts;

    public String getDumpId() { return dumpId; }
    public void setDumpId(String dumpId) { this.dumpId = dumpId; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }

    public String getTargetDatabase() { return targetDatabase; }
    public void setTargetDatabase(String targetDatabase) { this.targetDatabase = targetDatabase; }

    public boolean isCreateDatabase() { return createDatabase; }
    public void setCreateDatabase(boolean createDatabase) { this.createDatabase = createDatabase; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public List<String> getSelectedOptionalScripts() { return selectedOptionalScripts; }
    public void setSelectedOptionalScripts(List<String> selectedOptionalScripts) { this.selectedOptionalScripts = selectedOptionalScripts; }
}
