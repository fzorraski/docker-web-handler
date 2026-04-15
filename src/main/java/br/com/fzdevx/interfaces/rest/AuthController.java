package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.usecase.LoginUseCase;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

@Path("/auth")
public class AuthController {

    static final String SESSION_COOKIE = "DWH-SESSION";

    @Inject
    @ConfigProperty(name = "app.auth.enabled", defaultValue = "false")
    boolean authEnabled;

    @Inject
    @ConfigProperty(name = "app.auth.password")
    Optional<String> authPassword;

    @Inject
    AuthSessionManager sessionManager;

    @Inject
    LoginUseCase loginUseCase;

    @Inject
    @ConfigProperty(name = "app.rate-limit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    void onStartup(@Observes StartupEvent event) {
        if (authEnabled && (authPassword.isEmpty() || authPassword.get().isBlank())) {
            Log.warn("app.auth.enabled is true but app.auth.password is blank — all login attempts will be rejected.");
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStatus() {
        return Map.of("authEnabled", authEnabled);
    }

    @GET
    @Path("/check")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> checkSession(@CookieParam(SESSION_COOKIE) Cookie sessionCookie) {
        if (!authEnabled) {
            return Map.of("authenticated", true);
        }
        boolean valid = sessionCookie != null && sessionManager.validateAndTouch(sessionCookie.getValue());
        return Map.of("authenticated", valid);
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(Map<String, String> body, @Context HttpServerRequest request) {
        if (!authEnabled) {
            return Response.ok(Map.of("authenticated", true)).build();
        }

        String password = body != null ? body.get("password") : null;
        String clientIp = extractClientIp(request, trustForwardedHeaders);

        LoginResult result = loginUseCase.execute(clientIp, password);

        return switch (result.status()) {
            case SUCCESS -> {
                String sessionId = sessionManager.createSession();
                NewCookie cookie = new NewCookie.Builder(SESSION_COOKIE)
                        .value(sessionId)
                        .path("/")
                        .httpOnly(true)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .maxAge(sessionManager.getSessionTimeoutMinutes() * 60)
                        .build();
                yield Response.ok(Map.of("authenticated", true))
                        .cookie(cookie)
                        .build();
            }
            case INVALID_PASSWORD -> Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("code", "UNAUTHORIZED", "message", "Invalid password."))
                    .build();
            case RATE_LIMITED -> Response.status(429)
                    .header("Retry-After", result.retryAfterSeconds())
                    .entity(Map.of(
                            "code", "TOO_MANY_REQUESTS",
                            "message", "Too many failed attempts. Try again in " + result.retryAfterSeconds() + " seconds.",
                            "retryAfter", result.retryAfterSeconds()
                    ))
                    .build();
        };
    }

    @POST
    @Path("/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout(@CookieParam(SESSION_COOKIE) Cookie sessionCookie) {
        if (sessionCookie != null) {
            sessionManager.invalidateSession(sessionCookie.getValue());
        }

        NewCookie clearCookie = new NewCookie.Builder(SESSION_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge(0)
                .build();

        return Response.ok(Map.of("authenticated", false))
                .cookie(clearCookie)
                .build();
    }

    public static String extractClientIp(HttpServerRequest request, boolean trustForwardedHeaders) {
        if (trustForwardedHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
    }
}
