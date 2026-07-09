package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

    @Mock AuthSessionManager sessionManager;
    @Mock RateLimitPort rateLimitPort;
    @Mock br.com.fzdevx.infrastructure.config.RbacSettings rbacSettings;
    @Mock br.com.fzdevx.infrastructure.config.AuthorizationService authorizationService;
    @Mock br.com.fzdevx.infrastructure.config.CurrentUser currentUser;
    @Mock jakarta.inject.Provider<HttpServerRequest> vertxRequestProvider;
    @Mock HttpServerRequest httpServerRequest;
    @Mock SocketAddress remoteAddress;
    @Mock ContainerRequestContext requestContext;
    @Mock UriInfo uriInfo;

    @InjectMocks
    AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        setField("authEnabled", true);
        setField("ciEnabled", false);
        setField("ciApiKey", java.util.Optional.empty());
        setField("trustForwardedHeaders", false);
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        lenient().when(vertxRequestProvider.get()).thenReturn(httpServerRequest);
        lenient().when(remoteAddress.host()).thenReturn("127.0.0.1");
        lenient().when(httpServerRequest.remoteAddress()).thenReturn(remoteAddress);
    }

    private void setField(String name, Object value) {
        try {
            Field f = AuthenticationFilter.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(filter, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- auth disabled ----

    @Test
    void filter_authDisabled_passesThrough() {
        setField("authEnabled", false);
        when(uriInfo.getPath()).thenReturn("/containers/list");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    // ---- allowlisted paths ----

    @Test
    void filter_loginPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/login");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_logoutPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/logout");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_checkPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/check");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_statusPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/status");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    // ---- no cookie ----

    @Test
    void filter_noCookie_aborts401() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(401, captor.getValue().getStatus());
    }

    // ---- invalid session ----

    @Test
    void filter_invalidSession_aborts401() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "bad")));
        when(sessionManager.validateAndTouch("bad")).thenReturn(false);

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- valid session ----

    @Test
    void filter_validSession_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "valid")));
        when(sessionManager.validateAndTouch("valid")).thenReturn(true);

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    // ---- RBAC identity population ----

    @Test
    void filter_rbacValidSession_populatesCurrentUser() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "valid")));
        when(rbacSettings.isRbacEnabled()).thenReturn(true);
        when(sessionManager.getUserIdIfValid("valid")).thenReturn(java.util.Optional.of("user-1"));
        var resolved = new br.com.fzdevx.infrastructure.config.AuthorizationService.ResolvedUser(
                "user-1", "alice", java.util.List.of("role-1"), java.util.List.of("VIEWER"),
                java.util.List.of(), java.util.List.of(), true,
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_VIEW));
        when(authorizationService.resolve("user-1")).thenReturn(java.util.Optional.of(resolved));

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        verify(currentUser).set("user-1", "alice",
                java.util.Set.of(br.com.fzdevx.domain.model.auth.Permission.CONTAINERS_VIEW),
                new java.util.LinkedHashSet<>());
    }

    @Test
    void filter_rbacSessionOfDeletedUser_aborts401AndKillsSession() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "valid")));
        when(rbacSettings.isRbacEnabled()).thenReturn(true);
        when(sessionManager.getUserIdIfValid("valid")).thenReturn(java.util.Optional.of("gone"));
        when(authorizationService.resolve("gone")).thenReturn(java.util.Optional.empty());

        filter.filter(requestContext);

        verify(sessionManager).invalidateSession("valid");
        verify(requestContext).abortWith(any());
        verify(currentUser, never()).set(any(), any(), any(), any());
    }

    @Test
    void filter_rbacSessionOfDisabledUser_aborts401() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "valid")));
        when(rbacSettings.isRbacEnabled()).thenReturn(true);
        when(sessionManager.getUserIdIfValid("valid")).thenReturn(java.util.Optional.of("user-1"));
        var resolved = new br.com.fzdevx.infrastructure.config.AuthorizationService.ResolvedUser(
                "user-1", "alice", java.util.List.of("role-1"), java.util.List.of("VIEWER"),
                java.util.List.of(), java.util.List.of(), false, java.util.Set.of());
        when(authorizationService.resolve("user-1")).thenReturn(java.util.Optional.of(resolved));

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- non-allowlisted paths require auth ----

    @Test
    void filter_containersPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_imagesPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/images/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_schedulesPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/schedules/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_databasePath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/database/dumps/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- response body format ----

    @Test
    @SuppressWarnings("unchecked")
    void filter_401Response_hasCorrectBody() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Response response = captor.getValue();
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("UNAUTHORIZED", body.get("code"));
        assertEquals("Authentication required.", body.get("message"));
    }

    // ---- CI API key auth ----

    @Test
    void filter_ciPath_disabled_returns404() {
        setField("ciEnabled", false);
        when(uriInfo.getPath()).thenReturn("/ci/environments");

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(404, captor.getValue().getStatus());
    }

    @Test
    void filter_ciPath_noApiKey_returns401() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn(null);

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(401, captor.getValue().getStatus());
    }

    @Test
    void filter_ciPath_wrongApiKey_returns401() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn("wrong");

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_ciPath_validApiKey_passesThrough() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn("secret");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_ciPath_authIndependentOfAppAuth() {
        setField("authEnabled", false);
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn("secret");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_ciPath_requiresKeyEvenWhenAppAuthDisabled() {
        setField("authEnabled", false);
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn(null);

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- CI API key rate limiting ----

    @Test
    void filter_ciPath_rateLimited_returns429() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(rateLimitPort.checkRateLimit("ci:127.0.0.1")).thenReturn(Optional.of(30L));

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(429, captor.getValue().getStatus());
    }

    @Test
    void filter_ciPath_wrongKey_recordsFailure() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn("wrong");

        filter.filter(requestContext);

        verify(rateLimitPort).recordFailure("ci:127.0.0.1");
    }

    @Test
    void filter_ciPath_validKey_recordsSuccess() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(requestContext.getHeaderString("X-API-Key")).thenReturn("secret");

        filter.filter(requestContext);

        verify(rateLimitPort).recordSuccess("ci:127.0.0.1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void filter_ciPath_rateLimited_bodyContainsRetryAfter() {
        setField("ciEnabled", true);
        setField("ciApiKey", java.util.Optional.of("secret"));
        when(uriInfo.getPath()).thenReturn("/ci/environments");
        when(rateLimitPort.checkRateLimit("ci:127.0.0.1")).thenReturn(Optional.of(45L));

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getEntity();
        assertEquals("TOO_MANY_REQUESTS", body.get("code"));
        assertEquals(45L, body.get("retryAfter"));
    }
}
