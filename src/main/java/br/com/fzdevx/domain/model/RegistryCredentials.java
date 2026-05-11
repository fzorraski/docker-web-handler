package br.com.fzdevx.domain.model;

public record RegistryCredentials(String url, String username, String password, String registryPath) {

    public RegistryCredentials(String url, String username, String password) {
        this(url, username, password, "");
    }

    public boolean isDockerHub() {
        return url == null || url.isBlank();
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    public String host() {
        if (isDockerHub()) return "";
        return url.replaceAll("^https?://", "").replaceAll("/$", "");
    }

    /** Returns the registry path if configured, otherwise falls back to the given repository name. */
    public String resolvePathOrDefault(String repository) {
        return registryPath != null && !registryPath.isBlank() ? registryPath : repository;
    }

    @Override
    public String toString() {
        return "RegistryCredentials[url=" + url + ", username=" + username + ", password=***]";
    }
}
