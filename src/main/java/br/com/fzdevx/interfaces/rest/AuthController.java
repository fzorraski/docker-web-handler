package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.dto.LoginResult;
import br.com.fzdevx.application.usecase.LoginUseCase;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.AuthorizationService;
import br.com.fzdevx.infrastructure.config.RbacSettings;
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
    RbacSettings rbacSettings;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    br.com.fzdevx.application.usecase.ChangeOwnPasswordUseCase changeOwnPasswordUseCase;

    @Inject
    @ConfigProperty(name = "app.rate-limit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    void onStartup(@Observes StartupEvent event) {
        if (authEnabled && !rbacSettings.isRbacEnabled()
                && (authPassword.isEmpty() || authPassword.get().isBlank())) {
            Log.warn("app.auth.enabled is true but app.auth.password is blank — all login attempts will be rejected.");
        }
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStatus() {
        return Map.of("authEnabled", authEnabled,
                "rbacEnabled", rbacSettings.isRbacEnabled());
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMe(@CookieParam(SESSION_COOKIE) Cookie sessionCookie) {
        if (!rbacSettings.isRbacEnabled()) {
            boolean authenticated = !authEnabled
                    || (sessionCookie != null && sessionManager.validateAndTouch(sessionCookie.getValue()));
            return Response.ok(Map.of("rbac", false, "authenticated", authenticated)).build();
        }

        return Optional.ofNullable(sessionCookie)
                .flatMap(cookie -> sessionManager.getUserIdIfValid(cookie.getValue()))
                .flatMap(authorizationService::resolve)
                .filter(AuthorizationService.ResolvedUser::enabled)
                .map(user -> Response.ok(Map.of(
                        "rbac", true,
                        "userId", user.userId(),
                        "username", user.username(),
                        "roleIds", user.roleIds(),
                        "roleNames", user.roleNames(),
                        "tenants", tenantsOf(user),
                        "permissions", user.permissions().stream().map(Enum::name).sorted().toList()
                )).build())
                .orElseGet(() -> Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("code", "UNAUTHORIZED", "message", "Authentication required."))
                        .build());
    }

    private static java.util.List<Map<String, String>> tenantsOf(AuthorizationService.ResolvedUser user) {
        var tenants = new java.util.ArrayList<Map<String, String>>();
        for (int i = 0; i < user.tenantIds().size(); i++) {
            tenants.add(Map.of("id", user.tenantIds().get(i),
                    "name", user.tenantNames().get(i),
                    "color", user.tenantColors().get(i)));
        }
        return tenants;
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
        String username = body != null ? body.get("username") : null;
        String clientIp = extractClientIp(request, trustForwardedHeaders);

        LoginResult result = loginUseCase.execute(clientIp, username, password);

        return switch (result.status()) {
            case SUCCESS -> {
                String sessionId = sessionManager.createSession(result.userId());
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
    @Path("/change-password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changeOwnPassword(Map<String, String> body,
                                      @CookieParam(SESSION_COOKIE) Cookie sessionCookie) {
        if (!rbacSettings.isRbacEnabled()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("code", "NOT_FOUND", "message", "RBAC is not enabled."))
                    .build();
        }
        String sessionId = sessionCookie != null ? sessionCookie.getValue() : null;
        Optional<String> userId = sessionManager.getUserIdIfValid(sessionId);
        if (userId.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("code", "UNAUTHORIZED", "message", "Authentication required."))
                    .build();
        }
        changeOwnPasswordUseCase.execute(userId.get(), sessionId,
                body != null ? body.get("currentPassword") : null,
                body != null ? body.get("newPassword") : null);
        return Response.ok(Map.of("success", true)).build();
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
