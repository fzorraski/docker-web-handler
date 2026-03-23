package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.net.HttpCookie;
import java.util.List;

public class AuthWebSocketConfigurator extends ServerEndpointConfig.Configurator {

    static final String AUTH_RESULT_KEY = "auth.authenticated";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        AuthSessionManager sessionManager;
        try {
            sessionManager = CDI.current().select(AuthSessionManager.class).get();
        } catch (Exception e) {
            sec.getUserProperties().put(AUTH_RESULT_KEY, true);
            return;
        }

        if (!sessionManager.isAuthEnabled()) {
            sec.getUserProperties().put(AUTH_RESULT_KEY, true);
            return;
        }

        String sessionId = extractSessionCookie(request);
        boolean valid = sessionId != null && sessionManager.validateAndTouch(sessionId);
        sec.getUserProperties().put(AUTH_RESULT_KEY, valid);
    }

    private String extractSessionCookie(HandshakeRequest request) {
        List<String> cookieHeaders = request.getHeaders().get("cookie");
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            cookieHeaders = request.getHeaders().get("Cookie");
        }
        if (cookieHeaders == null) return null;

        for (String header : cookieHeaders) {
            try {
                List<HttpCookie> cookies = HttpCookie.parse(header);
                for (HttpCookie cookie : cookies) {
                    if (AuthController.SESSION_COOKIE.equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
