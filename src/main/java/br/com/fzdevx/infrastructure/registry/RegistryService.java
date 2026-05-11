package br.com.fzdevx.infrastructure.registry;

import br.com.fzdevx.application.port.RegistryPort;
import br.com.fzdevx.domain.model.RegistryCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.dockerjava.api.model.AuthConfig;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RegistryService implements RegistryPort {

    @Inject
    @ConfigProperty(name = "docker.registry.url")
    Optional<String> registryUrl;

    @Inject
    @ConfigProperty(name = "docker.registry.username")
    Optional<String> registryUsername;

    @Inject
    @ConfigProperty(name = "docker.registry.password")
    Optional<String> registryPassword;

    @Inject
    Config config;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Cache: "repository:tag" -> RegistryCredentials that owns that tag. */
    private final Map<String, RegistryCredentials> tagRegistryCache = new ConcurrentHashMap<>();

    // ── Public API ──────────────────────────────────────────────────────

    public List<String> fetchTags(String repository) throws Exception {
        List<RegistryCredentials> credentialsList = getCredentialsList(repository);
        LinkedHashSet<String> allTags = new LinkedHashSet<>();
        Exception lastException = null;

        for (RegistryCredentials creds : credentialsList) {
            try {
                List<String> tags = creds.isDockerHub()
                        ? fetchDockerHubTags(repository, creds)
                        : fetchV2RegistryTags(repository, creds);

                for (String tag : tags) {
                    allTags.add(tag);
                    tagRegistryCache.putIfAbsent(cacheKey(repository, tag), creds);
                }
            } catch (Exception e) {
                lastException = e;
            }
        }

        if (allTags.isEmpty() && lastException != null) {
            throw lastException;
        }

        return new ArrayList<>(allTags);
    }

    public AuthConfig buildAuthConfig(String repository, String tag) {
        RegistryCredentials creds = resolveCredentials(repository, tag);
        if (!creds.hasCredentials()) {
            return null;
        }
        AuthConfig authConfig = new AuthConfig()
                .withUsername(creds.username())
                .withPassword(creds.password());
        if (!creds.isDockerHub()) {
            authConfig.withRegistryAddress(creds.host());
        }
        return authConfig;
    }

    public String buildFullImageRef(String repository, String tag) {
        RegistryCredentials creds = resolveCredentials(repository, tag);
        if (!creds.isDockerHub()) {
            String path = creds.resolvePathOrDefault(repository);
            return creds.host() + "/" + path + ":" + tag;
        }
        String resolved = repository.contains("/") ? repository : resolveDockerHubPath(repository, creds);
        return resolved + ":" + tag;
    }

    // ── Config parsing ──────────────────────────────────────────────────

    private List<RegistryCredentials> getCredentialsList(String repository) {
        String rawUrl = config.getOptionalValue("repository.registry-url." + repository, String.class)
                .orElse(null);
        String rawUsername = config.getOptionalValue("repository.registry-username." + repository, String.class)
                .orElse(null);
        String rawPassword = config.getOptionalValue("repository.registry-password." + repository, String.class)
                .orElse(null);

        if (rawUrl == null && rawUsername == null && rawPassword == null) {
            return List.of(new RegistryCredentials(
                    registryUrl.orElse(""),
                    registryUsername.orElse(""),
                    registryPassword.orElse("")));
        }

        String rawPath = config.getOptionalValue("repository.registry-path." + repository, String.class)
                .orElse(null);

        String[] urls = rawUrl != null ? rawUrl.split("\\|", -1) : new String[]{""};
        String[] usernames = rawUsername != null ? rawUsername.split("\\|", -1) : new String[]{""};
        String[] passwords = rawPassword != null ? rawPassword.split("\\|", -1) : new String[]{""};
        String[] paths = rawPath != null ? rawPath.split("\\|", -1) : new String[]{""};

        int count = Math.max(urls.length, Math.max(usernames.length,
                Math.max(passwords.length, paths.length)));
        List<RegistryCredentials> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new RegistryCredentials(
                    i < urls.length ? urls[i].trim() : "",
                    i < usernames.length ? usernames[i].trim() : "",
                    i < passwords.length ? passwords[i].trim() : "",
                    i < paths.length ? paths[i].trim() : ""));
        }
        return result;
    }

    private RegistryCredentials resolveCredentials(String repository, String tag) {
        RegistryCredentials cached = tagRegistryCache.get(cacheKey(repository, tag));
        if (cached != null) {
            return cached;
        }
        return getCredentialsList(repository).getFirst();
    }

    private String cacheKey(String repository, String tag) {
        return repository + ":" + tag;
    }

    // ── Docker Hub ──────────────────────────────────────────────────────

    private String resolveDockerHubPath(String repository, RegistryCredentials creds) {
        if (repository.contains("/")) {
            return repository;
        }
        return creds.hasCredentials() ? creds.username() + "/" + repository : "library/" + repository;
    }

    private List<String> fetchDockerHubTags(String repository, RegistryCredentials creds) throws Exception {
        String path = resolveDockerHubPath(repository, creds);
        String token = obtainDockerHubToken(path, creds);

        String url = "https://registry-1.docker.io/v2/" + path + "/tags/list";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Docker Hub returned status " + response.statusCode()
                    + " for repository: " + repository);
        }

        return parseTagsResponse(response.body());
    }

    private String obtainDockerHubToken(String repositoryPath, RegistryCredentials creds) throws Exception {
        String url = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:"
                + repositoryPath + ":pull";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        if (creds.hasCredentials()) {
            String auth = Base64.getEncoder().encodeToString(
                    (creds.username() + ":" + creds.password()).getBytes());
            requestBuilder.header("Authorization", "Basic " + auth);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to obtain Docker Hub token (status "
                    + response.statusCode() + "). Check your credentials.");
        }

        try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
            return reader.readObject().getString("token");
        }
    }

    // ── V2 Registry ─────────────────────────────────────────────────────

    private List<String> fetchV2RegistryTags(String repository, RegistryCredentials creds) throws Exception {
        String baseUrl = creds.url().replaceAll("/$", "");
        String path = creds.resolvePathOrDefault(repository);
        String url = baseUrl + "/v2/" + path + "/tags/list";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        if (creds.hasCredentials()) {
            String auth = Base64.getEncoder().encodeToString(
                    (creds.username() + ":" + creds.password()).getBytes());
            requestBuilder.header("Authorization", "Basic " + auth);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

        // Handle token-based auth (GitLab, GitHub GHCR, etc.)
        if (response.statusCode() == 401 && creds.hasCredentials()) {
            String bearerToken = obtainV2Token(response, creds);
            if (bearerToken != null) {
                HttpRequest tokenRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + bearerToken)
                        .GET()
                        .build();
                response = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            }
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("Registry returned status " + response.statusCode()
                    + " for repository: " + repository);
        }

        return parseTagsResponse(response.body());
    }

    /**
     * Parses the Www-Authenticate header from a 401 response and exchanges credentials for a Bearer token.
     * Supports the format: Bearer realm="...",service="...",scope="..."
     */
    private String obtainV2Token(HttpResponse<String> challengeResponse, RegistryCredentials creds) throws Exception {
        String wwwAuth = challengeResponse.headers()
                .firstValue("Www-Authenticate")
                .or(() -> challengeResponse.headers().firstValue("www-authenticate"))
                .orElse(null);
        if (wwwAuth == null || !wwwAuth.startsWith("Bearer ")) {
            return null;
        }

        String realm = extractAuthParam(wwwAuth, "realm");
        String service = extractAuthParam(wwwAuth, "service");
        String scope = extractAuthParam(wwwAuth, "scope");
        if (realm == null) {
            return null;
        }

        StringBuilder tokenUrl = new StringBuilder(realm);
        tokenUrl.append(realm.contains("?") ? "&" : "?");
        if (service != null) tokenUrl.append("service=").append(service).append("&");
        if (scope != null) tokenUrl.append("scope=").append(scope);

        String basicAuth = Base64.getEncoder().encodeToString(
                (creds.username() + ":" + creds.password()).getBytes());

        HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl.toString()))
                .header("Authorization", "Basic " + basicAuth)
                .GET()
                .build();

        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        if (tokenResponse.statusCode() != 200) {
            return null;
        }

        try (JsonReader reader = Json.createReader(new StringReader(tokenResponse.body()))) {
            JsonObject json = reader.readObject();
            if (json.containsKey("token")) return json.getString("token");
            if (json.containsKey("access_token")) return json.getString("access_token");
        }
        return null;
    }

    private String extractAuthParam(String header, String param) {
        String prefix = param + "=\"";
        int start = header.indexOf(prefix);
        if (start < 0) return null;
        start += prefix.length();
        int end = header.indexOf('"', start);
        return end > start ? header.substring(start, end) : null;
    }

    // ── Shared ──────────────────────────────────────────────────────────

    private List<String> parseTagsResponse(String body) {
        List<String> tags = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
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
