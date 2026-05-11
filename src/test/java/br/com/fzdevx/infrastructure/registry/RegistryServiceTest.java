package br.com.fzdevx.infrastructure.registry;

import br.com.fzdevx.domain.model.RegistryCredentials;
import com.github.dockerjava.api.model.AuthConfig;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistryServiceTest {

    @Mock Config config;

    @InjectMocks RegistryService service;

    @BeforeEach
    void setUp() throws Exception {
        setField("registryUrl", Optional.of(""));
        setField("registryUsername", Optional.of(""));
        setField("registryPassword", Optional.of(""));
    }

    // ── buildFullImageRef ───────────────────────────────────────────────

    @Test
    void buildFullImageRef_v2Registry_withRegistryPath_usesPath() {
        mockPerRepoConfig("myapp",
                "https://registry.gitlab.com",
                "gitlab-user",
                "gitlab-pass",
                "sparkag/mywms/myapp");

        String ref = service.buildFullImageRef("myapp", "1.0");

        assertEquals("registry.gitlab.com/sparkag/mywms/myapp:1.0", ref);
    }

    @Test
    void buildFullImageRef_v2Registry_withoutRegistryPath_usesRepoName() {
        mockPerRepoConfig("myapp",
                "https://registry.example.com",
                "user",
                "pass",
                null);

        String ref = service.buildFullImageRef("myapp", "latest");

        assertEquals("registry.example.com/myapp:latest", ref);
    }

    @Test
    void buildFullImageRef_dockerHub_ignoresRegistryPath() {
        mockPerRepoConfig("myapp",
                "",
                "hubuser",
                "hubpass",
                "some/ignored/path");

        String ref = service.buildFullImageRef("myapp", "latest");

        assertEquals("hubuser/myapp:latest", ref);
    }

    @Test
    void buildFullImageRef_pipeSeparated_firstIsDockerHub_usesHubPath() {
        mockPerRepoConfig("myapp",
                "|https://registry.gitlab.com",
                "hubuser|gitlabuser",
                "hubpass|gitlabpass",
                "|group/project/myapp");

        // No cache hit, so resolveCredentials falls back to first entry (Docker Hub)
        String ref = service.buildFullImageRef("myapp", "v1");

        assertEquals("hubuser/myapp:v1", ref);
    }

    @Test
    void buildFullImageRef_cachedGitlabCreds_usesRegistryPath() throws Exception {
        mockPerRepoConfig("myapp",
                "|https://registry.gitlab.com",
                "hubuser|gitlabuser",
                "hubpass|gitlabpass",
                "|group/project/myapp");

        // Simulate that a tag was fetched from the GitLab registry and cached
        var gitlabCreds = new RegistryCredentials(
                "https://registry.gitlab.com", "gitlabuser", "gitlabpass", "group/project/myapp");
        putInCache("myapp", "gl-tag", gitlabCreds);

        String ref = service.buildFullImageRef("myapp", "gl-tag");

        assertEquals("registry.gitlab.com/group/project/myapp:gl-tag", ref);
    }

    @Test
    void buildFullImageRef_v2Registry_stripsTrailingSlashFromHost() {
        mockPerRepoConfig("myapp",
                "https://registry.gitlab.com/",
                "user",
                "pass",
                "org/repo/myapp");

        String ref = service.buildFullImageRef("myapp", "2.0");

        assertEquals("registry.gitlab.com/org/repo/myapp:2.0", ref);
    }

    // ── buildAuthConfig ─────────────────────────────────────────────────

    @Test
    void buildAuthConfig_v2Registry_setsRegistryAddress() {
        mockPerRepoConfig("myapp",
                "https://registry.gitlab.com",
                "gitlabuser",
                "gitlabpass",
                "group/project/myapp");

        AuthConfig auth = service.buildAuthConfig("myapp", "1.0");

        assertNotNull(auth);
        assertEquals("gitlabuser", auth.getUsername());
        assertEquals("gitlabpass", auth.getPassword());
        assertEquals("registry.gitlab.com", auth.getRegistryAddress());
    }

    @Test
    void buildAuthConfig_noCredentials_returnsNull() throws Exception {
        setField("registryUrl", Optional.of(""));
        setField("registryUsername", Optional.of(""));
        setField("registryPassword", Optional.of(""));

        when(config.getOptionalValue("repository.registry-url.myapp", String.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-username.myapp", String.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-password.myapp", String.class)).thenReturn(Optional.empty());

        assertNull(service.buildAuthConfig("myapp", "latest"));
    }

    // ── getCredentialsList (tested via buildFullImageRef) ────────────────

    @Test
    void getCredentialsList_noPerRepoConfig_fallsBackToGlobal() throws Exception {
        setField("registryUrl", Optional.of("https://global.registry.io"));
        setField("registryUsername", Optional.of("globaluser"));
        setField("registryPassword", Optional.of("globalpass"));

        when(config.getOptionalValue("repository.registry-url.myapp", String.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-username.myapp", String.class)).thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-password.myapp", String.class)).thenReturn(Optional.empty());

        String ref = service.buildFullImageRef("myapp", "latest");

        assertEquals("global.registry.io/myapp:latest", ref);
    }

    @Test
    void getCredentialsList_parsesThreeSegments_withPaths() throws Exception {
        mockPerRepoConfig("myapp",
                "||https://registry.gitlab.com",
                "hub1|hub2|gitlabuser",
                "pass1|pass2|gitlabpass",
                "||org/project/myapp");

        // First entry (Docker Hub with hub1) is used by default (no cache)
        String ref = service.buildFullImageRef("myapp", "uncached-tag");
        assertEquals("hub1/myapp:uncached-tag", ref);

        // Simulate cached GitLab entry (third segment)
        var gitlabCreds = new RegistryCredentials(
                "https://registry.gitlab.com", "gitlabuser", "gitlabpass", "org/project/myapp");
        putInCache("myapp", "gitlab-tag", gitlabCreds);

        String gitlabRef = service.buildFullImageRef("myapp", "gitlab-tag");
        assertEquals("registry.gitlab.com/org/project/myapp:gitlab-tag", gitlabRef);
    }

    @Test
    void getCredentialsList_emptyPathSegments_fallBackToRepoName() throws Exception {
        mockPerRepoConfig("myapp",
                "|https://registry.example.com",
                "hubuser|privuser",
                "hubpass|privpass",
                "|");

        // Simulate cached second entry (private V2 registry with empty path)
        var privateCreds = new RegistryCredentials(
                "https://registry.example.com", "privuser", "privpass", "");
        putInCache("myapp", "priv-tag", privateCreds);

        String ref = service.buildFullImageRef("myapp", "priv-tag");
        assertEquals("registry.example.com/myapp:priv-tag", ref);
    }

    @Test
    void getCredentialsList_pathOnlyConfigured_othersMissing() {
        when(config.getOptionalValue("repository.registry-url.myapp", String.class))
                .thenReturn(Optional.of("https://registry.gitlab.com"));
        when(config.getOptionalValue("repository.registry-username.myapp", String.class))
                .thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-password.myapp", String.class))
                .thenReturn(Optional.empty());
        when(config.getOptionalValue("repository.registry-path.myapp", String.class))
                .thenReturn(Optional.of("org/project/myapp"));

        String ref = service.buildFullImageRef("myapp", "1.0");

        assertEquals("registry.gitlab.com/org/project/myapp:1.0", ref);
    }

    // ── extractAuthParam ─────────────────────────────────────────────────

    @Test
    void extractAuthParam_parsesRealm() throws Exception {
        String header = "Bearer realm=\"https://gitlab.com/jwt/auth\",service=\"container_registry\",scope=\"repository:group/app:pull\"";

        assertEquals("https://gitlab.com/jwt/auth", invokeExtractAuthParam(header, "realm"));
    }

    @Test
    void extractAuthParam_parsesService() throws Exception {
        String header = "Bearer realm=\"https://gitlab.com/jwt/auth\",service=\"container_registry\",scope=\"repository:group/app:pull\"";

        assertEquals("container_registry", invokeExtractAuthParam(header, "service"));
    }

    @Test
    void extractAuthParam_parsesScope() throws Exception {
        String header = "Bearer realm=\"https://gitlab.com/jwt/auth\",service=\"container_registry\",scope=\"repository:group/app:pull\"";

        assertEquals("repository:group/app:pull", invokeExtractAuthParam(header, "scope"));
    }

    @Test
    void extractAuthParam_returnsNull_whenParamMissing() throws Exception {
        String header = "Bearer realm=\"https://gitlab.com/jwt/auth\"";

        assertNull(invokeExtractAuthParam(header, "service"));
    }

    @Test
    void extractAuthParam_returnsNull_whenHeaderEmpty() throws Exception {
        assertNull(invokeExtractAuthParam("", "realm"));
    }

    // ── V2 token auth flow (fetchTags with local HTTP server) ───────────

    private HttpServer httpServer;

    @AfterEach
    void tearDownServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    @Test
    void fetchTags_v2TokenAuth_exchangesTokenAndRetries() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String baseUrl = "http://localhost:" + port;

        // /v2/.../tags/list → 401 with Www-Authenticate challenge
        httpServer.createContext("/v2/group/app/tags/list", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Second request with Bearer token → return tags
                byte[] body = "{\"tags\":[\"1.0\",\"2.0\"]}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                // First request → 401 challenge
                exchange.getResponseHeaders().add("Www-Authenticate",
                        "Bearer realm=\"" + baseUrl + "/token\",service=\"registry\",scope=\"repository:group/app:pull\"");
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.close();
        });

        // /token → return Bearer token
        httpServer.createContext("/token", exchange -> {
            byte[] body = "{\"token\":\"test-bearer-token\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        httpServer.start();

        mockPerRepoConfig("myapp", baseUrl, "user", "pass", "group/app");
        replaceHttpClient();

        List<String> tags = service.fetchTags("myapp");

        assertEquals(List.of("1.0", "2.0"), tags);
    }

    @Test
    void fetchTags_v2TokenAuth_accessTokenField() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String baseUrl = "http://localhost:" + port;

        httpServer.createContext("/v2/myapp/tags/list", exchange -> {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                byte[] body = "{\"tags\":[\"latest\"]}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.getResponseHeaders().add("Www-Authenticate",
                        "Bearer realm=\"" + baseUrl + "/token\",service=\"reg\"");
                exchange.sendResponseHeaders(401, -1);
            }
            exchange.close();
        });

        // Token response uses "access_token" instead of "token"
        httpServer.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"at-12345\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        httpServer.start();

        mockPerRepoConfig("myapp", baseUrl, "user", "pass", null);
        replaceHttpClient();

        List<String> tags = service.fetchTags("myapp");

        assertEquals(List.of("latest"), tags);
    }

    @Test
    void fetchTags_v2BasicAuth_returnsDirectly_whenRegistryAcceptsBasic() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String baseUrl = "http://localhost:" + port;

        httpServer.createContext("/v2/myapp/tags/list", exchange -> {
            byte[] body = "{\"tags\":[\"v1\",\"v2\"]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        httpServer.start();

        mockPerRepoConfig("myapp", baseUrl, "user", "pass", null);
        replaceHttpClient();

        List<String> tags = service.fetchTags("myapp");

        assertEquals(List.of("v1", "v2"), tags);
    }

    @Test
    void fetchTags_v2TokenAuth_throws_whenTokenExchangeFails() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String baseUrl = "http://localhost:" + port;

        httpServer.createContext("/v2/myapp/tags/list", exchange -> {
            exchange.getResponseHeaders().add("Www-Authenticate",
                    "Bearer realm=\"" + baseUrl + "/token\",service=\"reg\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        // Token endpoint returns 403
        httpServer.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        httpServer.start();

        mockPerRepoConfig("myapp", baseUrl, "user", "pass", null);
        replaceHttpClient();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.fetchTags("myapp"));
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    void fetchTags_v2TokenAuth_throws_whenNoWwwAuthenticateHeader() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = httpServer.getAddress().getPort();
        String baseUrl = "http://localhost:" + port;

        // 401 without Www-Authenticate header
        httpServer.createContext("/v2/myapp/tags/list", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        httpServer.start();

        mockPerRepoConfig("myapp", baseUrl, "user", "pass", null);
        replaceHttpClient();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.fetchTags("myapp"));
        assertTrue(ex.getMessage().contains("401"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void mockPerRepoConfig(String repo, String url, String username, String password, String path) {
        when(config.getOptionalValue("repository.registry-url." + repo, String.class))
                .thenReturn(Optional.ofNullable(url));
        when(config.getOptionalValue("repository.registry-username." + repo, String.class))
                .thenReturn(Optional.ofNullable(username));
        when(config.getOptionalValue("repository.registry-password." + repo, String.class))
                .thenReturn(Optional.ofNullable(password));
        when(config.getOptionalValue("repository.registry-path." + repo, String.class))
                .thenReturn(Optional.ofNullable(path));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RegistryService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @SuppressWarnings("unchecked")
    private void putInCache(String repo, String tag, RegistryCredentials creds) throws Exception {
        Field cacheField = RegistryService.class.getDeclaredField("tagRegistryCache");
        cacheField.setAccessible(true);
        Map<String, RegistryCredentials> cache = (Map<String, RegistryCredentials>) cacheField.get(service);
        cache.put(repo + ":" + tag, creds);
    }

    private String invokeExtractAuthParam(String header, String param) throws Exception {
        Method method = RegistryService.class.getDeclaredMethod("extractAuthParam", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, header, param);
    }

    private void replaceHttpClient() throws Exception {
        Field clientField = RegistryService.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(service, HttpClient.newHttpClient());
    }
}
