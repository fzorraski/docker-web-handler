package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AuthWebSocketConfigurator extends ServerEndpointConfig.Configurator {

    static final String AUTH_RESULT_KEY = "auth.authenticated";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        AuthSessionManager sessionManager;
        try {
            sessionManager = CDI.current().select(AuthSessionManager.class).get();
        } catch (Exception e) {
            // Fail closed: if we cannot resolve the auth manager we cannot prove
            // the request is authenticated, so deny rather than grant access.
            Log.errorf("Terminal WebSocket auth check failed to resolve AuthSessionManager: %s", e.getMessage());
            sec.getUserProperties().put(AUTH_RESULT_KEY, false);
            return;
        }

        if (!sessionManager.isAuthEnabled()) {
            sec.getUserProperties().put(AUTH_RESULT_KEY, true);
            return;
        }

        // A browser may legitimately send more than one DWH-SESSION cookie
        // (e.g. a stale duplicate scoped to a different path alongside the
        // current one). Accept the handshake if any of them is valid.
        boolean valid = false;
        for (String sessionId : extractSessionCookies(request)) {
            if (sessionManager.validateAndTouch(sessionId)) {
                valid = true;
                break;
            }
        }
        sec.getUserProperties().put(AUTH_RESULT_KEY, valid);
    }

    private List<String> extractSessionCookies(HandshakeRequest request) {
        Map<String, List<String>> headers = request.getHeaders();
        if (headers == null) return List.of();

        // HTTP header names are case-insensitive; the handshake map may not be.
        List<String> cookieHeaders = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                cookieHeaders.addAll(entry.getValue());
            }
        }
        return extractSessionCookies(cookieHeaders);
    }

    /**
     * Parses the Cookie request header(s) manually and returns every
     * {@value AuthController#SESSION_COOKIE} value found, in header order.
     * <p>
     * {@code java.net.HttpCookie.parse} targets Set-Cookie response headers and
     * chokes on values containing '$' (e.g. Google Analytics _ga_* cookies use
     * $-delimited segments), throwing IllegalArgumentException for the
     * <em>entire</em> header and dropping DWH-SESSION along with it — which
     * surfaced as a bogus "Authentication required." on the terminal WebSocket.
     */
    static List<String> extractSessionCookies(List<String> cookieHeaders) {
        if (cookieHeaders == null) return List.of();

        List<String> values = new ArrayList<>();
        for (String header : cookieHeaders) {
            if (header == null) continue;
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = pair.substring(0, eq).trim();
                if (AuthController.SESSION_COOKIE.equals(name)) {
                    values.add(pair.substring(eq + 1).trim());
                }
            }
        }
        return values;
    }
}
