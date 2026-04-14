package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.usecase.LoginUseCase;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock AuthSessionManager sessionManager;
    @Mock LoginUseCase loginUseCase;
    @Mock HttpServerRequest httpServerRequest;
    @Mock SocketAddress remoteAddress;

    @InjectMocks
    AuthController controller;

    @BeforeEach
    void setUp() {
        setField("authEnabled", true);
        setField("authPassword", Optional.of("secret"));
        setField("trustForwardedHeaders", false);
        when(remoteAddress.host()).thenReturn("127.0.0.1");
        when(httpServerRequest.remoteAddress()).thenReturn(remoteAddress);
    }

    private void setField(String name, Object value) {
        try {
            Field f = AuthController.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(controller, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- getStatus ----

    @Test
    void getStatus_authEnabled_returnsTrue() {
        Map<String, Object> result = controller.getStatus();
        assertEquals(true, result.get("authEnabled"));
    }

    @Test
    void getStatus_authDisabled_returnsFalse() {
        setField("authEnabled", false);
        Map<String, Object> result = controller.getStatus();
        assertEquals(false, result.get("authEnabled"));
    }

    // ---- checkSession ----

    @Test
    void checkSession_authDisabled_returnsAuthenticated() {
        setField("authEnabled", false);
        Map<String, Object> result = controller.checkSession(null);
        assertEquals(true, result.get("authenticated"));
    }

    @Test
    void checkSession_noCookie_returnsFalse() {
        Map<String, Object> result = controller.checkSession(null);
        assertEquals(false, result.get("authenticated"));
    }

    @Test
    void checkSession_invalidSession_returnsFalse() {
        Cookie cookie = new Cookie("DWH-SESSION", "bad-id");
        when(sessionManager.validateAndTouch("bad-id")).thenReturn(false);
        Map<String, Object> result = controller.checkSession(cookie);
        assertEquals(false, result.get("authenticated"));
    }

    @Test
    void checkSession_validSession_returnsTrue() {
        Cookie cookie = new Cookie("DWH-SESSION", "valid-id");
        when(sessionManager.validateAndTouch("valid-id")).thenReturn(true);
        Map<String, Object> result = controller.checkSession(cookie);
        assertEquals(true, result.get("authenticated"));
    }

    // ---- login ----

    @Test
    void login_authDisabled_returns200() {
        setField("authEnabled", false);
        Response response = controller.login(Map.of("password", "anything"), httpServerRequest);
        assertEquals(200, response.getStatus());
    }

    @Test
    void login_success_returns200WithCookie() {
        when(loginUseCase.execute("127.0.0.1", "secret")).thenReturn(LoginResult.success());
        when(sessionManager.createSession()).thenReturn("session-123");
        when(sessionManager.getSessionTimeoutMinutes()).thenReturn(480);

        Response response = controller.login(Map.of("password", "secret"), httpServerRequest);

        assertEquals(200, response.getStatus());
        Map<String, NewCookie> cookies = response.getCookies();
        assertTrue(cookies.containsKey("DWH-SESSION"));
        NewCookie cookie = cookies.get("DWH-SESSION");
        assertEquals("session-123", cookie.getValue());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertEquals(480 * 60, cookie.getMaxAge());
    }

    @Test
    void login_invalidPassword_returns401() {
        when(loginUseCase.execute("127.0.0.1", "wrong")).thenReturn(LoginResult.invalidPassword());

        Response response = controller.login(Map.of("password", "wrong"), httpServerRequest);
        assertEquals(401, response.getStatus());
    }

    @Test
    void login_nullBody_returns401() {
        when(loginUseCase.execute(anyString(), isNull())).thenReturn(LoginResult.invalidPassword());

        Response response = controller.login(null, httpServerRequest);
        assertEquals(401, response.getStatus());
    }

    @Test
    void login_rateLimited_returns429() {
        when(loginUseCase.execute("127.0.0.1", "anything")).thenReturn(LoginResult.rateLimited(30));

        Response response = controller.login(Map.of("password", "anything"), httpServerRequest);

        assertEquals(429, response.getStatus());
        assertEquals(30L, response.getHeaderString("Retry-After") != null
                ? Long.parseLong(response.getHeaderString("Retry-After")) : null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void login_rateLimited_bodyContainsRetryAfter() {
        when(loginUseCase.execute("127.0.0.1", "anything")).thenReturn(LoginResult.rateLimited(45));

        Response response = controller.login(Map.of("password", "anything"), httpServerRequest);

        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("TOO_MANY_REQUESTS", body.get("code"));
        assertEquals(45L, body.get("retryAfter"));
    }

    // ---- logout ----

    @Test
    void logout_withCookie_invalidatesSession() {
        Cookie cookie = new Cookie("DWH-SESSION", "session-123");
        Response response = controller.logout(cookie);

        assertEquals(200, response.getStatus());
        verify(sessionManager).invalidateSession("session-123");
        NewCookie clearCookie = response.getCookies().get("DWH-SESSION");
        assertNotNull(clearCookie);
        assertEquals(0, clearCookie.getMaxAge());
    }

    @Test
    void logout_noCookie_returns200() {
        Response response = controller.logout(null);
        assertEquals(200, response.getStatus());
        verify(sessionManager, never()).invalidateSession(any());
    }

    // ---- cookie security properties ----

    @Test
    void login_cookie_isHttpOnly() {
        when(loginUseCase.execute("127.0.0.1", "secret")).thenReturn(LoginResult.success());
        when(sessionManager.createSession()).thenReturn("id");
        when(sessionManager.getSessionTimeoutMinutes()).thenReturn(60);

        Response response = controller.login(Map.of("password", "secret"), httpServerRequest);
        NewCookie cookie = response.getCookies().get("DWH-SESSION");

        assertTrue(cookie.isHttpOnly());
    }

    @Test
    void login_cookie_hasSameSiteStrict() {
        when(loginUseCase.execute("127.0.0.1", "secret")).thenReturn(LoginResult.success());
        when(sessionManager.createSession()).thenReturn("id");
        when(sessionManager.getSessionTimeoutMinutes()).thenReturn(60);

        Response response = controller.login(Map.of("password", "secret"), httpServerRequest);
        NewCookie cookie = response.getCookies().get("DWH-SESSION");

        assertEquals(NewCookie.SameSite.STRICT, cookie.getSameSite());
    }

    // ---- extractClientIp ----

    @Test
    void extractClientIp_trustDisabled_ignoresForwardedHeader() {
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");

        assertEquals("127.0.0.1", AuthController.extractClientIp(httpServerRequest, false));
    }

    @Test
    void extractClientIp_trustEnabled_usesForwardedHeader() {
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 70.41.3.18");

        assertEquals("203.0.113.50", AuthController.extractClientIp(httpServerRequest, true));
    }

    @Test
    void extractClientIp_trustEnabled_blankHeader_usesRemoteAddr() {
        SocketAddress addr = mock(SocketAddress.class);
        when(addr.host()).thenReturn("10.0.0.1");
        when(httpServerRequest.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(httpServerRequest.remoteAddress()).thenReturn(addr);

        assertEquals("10.0.0.1", AuthController.extractClientIp(httpServerRequest, true));
    }

    @Test
    void extractClientIp_noForwardedHeader_usesRemoteAddr() {
        SocketAddress addr = mock(SocketAddress.class);
        when(addr.host()).thenReturn("10.0.0.1");
        when(httpServerRequest.remoteAddress()).thenReturn(addr);

        assertEquals("10.0.0.1", AuthController.extractClientIp(httpServerRequest, true));
    }
}
