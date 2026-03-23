package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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
    public Response login(Map<String, String> body) {
        if (!authEnabled) {
            return Response.ok(Map.of("authenticated", true)).build();
        }

        String password = body != null ? body.get("password") : null;

        if (!validatePassword(password)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("code", "UNAUTHORIZED", "message", "Invalid password."))
                    .build();
        }

        String sessionId = sessionManager.createSession();
        NewCookie cookie = new NewCookie.Builder(SESSION_COOKIE)
                .value(sessionId)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .maxAge(sessionManager.getSessionTimeoutMinutes() * 60)
                .build();

        return Response.ok(Map.of("authenticated", true))
                .cookie(cookie)
                .build();
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

    private boolean validatePassword(String input) {
        return PasswordValidationService.constantTimeEquals(authPassword, input);
    }
}
