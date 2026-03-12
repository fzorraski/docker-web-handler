package br.com.fzdevx.model;


public class Response {

    private int state;

    private String message;

    private java.util.List<String> tags;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public java.util.List<String> getTags() {
        return tags;
    }

    public void setTags(java.util.List<String> tags) {
        this.tags = tags;
    }

    private java.util.List<String> databases;

    private String dbEnvVar;

    public java.util.List<String> getDatabases() {
        return databases;
    }

    public void setDatabases(java.util.List<String> databases) {
        this.databases = databases;
    }

    public String getDbEnvVar() {
        return dbEnvVar;
    }

    public void setDbEnvVar(String dbEnvVar) {
        this.dbEnvVar = dbEnvVar;
    }

}
