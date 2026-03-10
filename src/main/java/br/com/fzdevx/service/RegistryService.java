package br.com.fzdevx.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.dockerjava.api.model.AuthConfig;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RegistryService {

    @Inject
    @ConfigProperty(name = "docker.registry.url")
    Optional<String> registryUrl;

    @Inject
    @ConfigProperty(name = "docker.registry.username")
    Optional<String> registryUsername;

    @Inject
    @ConfigProperty(name = "docker.registry.password")
    Optional<String> registryPassword;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String getRegistryUrl() {
        return registryUrl.orElse("");
    }

    private String getUsername() {
        return registryUsername.orElse("");
    }

    private String getPassword() {
        return registryPassword.orElse("");
    }

    private boolean hasCredentials() {
        return !getUsername().isBlank() && !getPassword().isBlank();
    }

    public List<String> fetchTags(String repository) throws Exception {
        if (getRegistryUrl().isBlank()) {
            return fetchDockerHubTags(repository);
        } else {
            return fetchV2RegistryTags(repository);
        }
    }

    public AuthConfig buildAuthConfig() {
        if (!hasCredentials()) {
            return null;
        }
        AuthConfig authConfig = new AuthConfig()
                .withUsername(getUsername())
                .withPassword(getPassword());
        if (!getRegistryUrl().isBlank()) {
            String host = getRegistryUrl().replaceAll("^https?://", "").replaceAll("/$", "");
            authConfig.withRegistryAddress(host);
        }
        return authConfig;
    }

    public String buildFullImageRef(String repository, String tag) {
        if (!getRegistryUrl().isBlank()) {
            String host = getRegistryUrl().replaceAll("^https?://", "").replaceAll("/$", "");
            return host + "/" + repository + ":" + tag;
        }
        // Docker Hub: resolve namespace for short names (e.g. mywms-spk -> fabriciozrk/mywms-spk)
        String resolved = repository.contains("/") ? repository : resolveDockerHubPath(repository);
        return resolved + ":" + tag;
    }

    private String resolveDockerHubPath(String repository) {
        if (repository.contains("/")) {
            return repository;
        }
        // Private repo: use username as namespace (e.g. fabriciozrk/mywms-spk)
        // Public official image: use library/ namespace (e.g. library/nginx)
        return hasCredentials() ? getUsername() + "/" + repository : "library/" + repository;
    }

    private List<String> fetchDockerHubTags(String repository) throws Exception {
        String path = resolveDockerHubPath(repository);

        // Docker Hub v2 API requires a JWT token, even for public repos.
        // For private repos, the token request must include credentials.
        String token = obtainDockerHubToken(path);

        String url = "https://registry-1.docker.io/v2/" + path + "/tags/list";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        requestBuilder.header("Authorization", "Bearer " + token);

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Docker Hub returned status " + response.statusCode()
                    + " for repository: " + repository);
        }

        List<String> tags = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonObject json = reader.readObject();
            JsonArray tagsArray = json.getJsonArray("tags");
            if (tagsArray != null) {
                for (int i = 0; i < tagsArray.size(); i++) {
                    tags.add(tagsArray.getString(i));
                }
            }
        }
        return tags;
    }

    private String obtainDockerHubToken(String repositoryPath) throws Exception {
        String url = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:"
                + repositoryPath + ":pull";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        if (hasCredentials()) {
            String auth = Base64.getEncoder().encodeToString(
                    (getUsername() + ":" + getPassword()).getBytes());
            requestBuilder.header("Authorization", "Basic " + auth);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to obtain Docker Hub token (status "
                    + response.statusCode() + "). Check your credentials.");
        }

        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonObject json = reader.readObject();
            return json.getString("token");
        }
    }

    private List<String> fetchV2RegistryTags(String repository) throws Exception {
        String baseUrl = getRegistryUrl().replaceAll("/$", "");
        String url = baseUrl + "/v2/" + repository + "/tags/list";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        if (hasCredentials()) {
            String auth = Base64.getEncoder().encodeToString(
                    (getUsername() + ":" + getPassword()).getBytes());
            requestBuilder.header("Authorization", "Basic " + auth);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Registry returned status " + response.statusCode()
                    + " for repository: " + repository);
        }

        List<String> tags = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            JsonObject json = reader.readObject();
            JsonArray tagsArray = json.getJsonArray("tags");
            if (tagsArray != null) {
                for (int i = 0; i < tagsArray.size(); i++) {
                    tags.add(tagsArray.getString(i));
                }
            }
        }
        return tags;
    }
}
