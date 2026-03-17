package br.com.fzdevx.domain.model;

public record RegistryCredentials(String url, String username, String password) {

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

    @Override
    public String toString() {
        return "RegistryCredentials[url=" + url + ", username=" + username + ", password=***]";
    }
}
