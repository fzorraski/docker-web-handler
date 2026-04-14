package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Provider
@PreMatching
public class AuthenticationFilter implements ContainerRequestFilter {

    private static final Set<String> ALLOWLISTED_PATHS = Set.of(
            "/auth/login",
            "/auth/logout",
            "/auth/check",
            "/auth/status"
    );

    @Inject
    @ConfigProperty(name = "app.auth.enabled", defaultValue = "false")
    boolean authEnabled;

    @Inject
    @ConfigProperty(name = "ci.api.enabled", defaultValue = "false")
    boolean ciEnabled;

    @Inject
    @ConfigProperty(name = "ci.api.key")
    Optional<String> ciApiKey;

    @Inject
    @ConfigProperty(name = "app.rate-limit.trust-forwarded-headers", defaultValue = "false")
    boolean trustForwardedHeaders;

    @Inject
    AuthSessionManager sessionManager;

    @Inject
    RateLimitPort rateLimitPort;

    @Inject
    jakarta.inject.Provider<HttpServerRequest> vertxRequestProvider;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();

        // CI API key auth (independent of app.auth.enabled)
        if (path.startsWith("/ci/")) {
            if (!ciEnabled) {
                abort(requestContext, 404, "NOT_FOUND", "CI API is not enabled.");
                return;
            }

            String clientIp = extractClientIp();
            String rateLimitKey = "ci:" + clientIp;

            Optional<Long> blocked = rateLimitPort.checkRateLimit(rateLimitKey);
            if (blocked.isPresent()) {
                abortRateLimited(requestContext, blocked.get());
                return;
            }

            String apiKey = requestContext.getHeaderString("X-API-Key");
            if (!PasswordValidationService.constantTimeEquals(ciApiKey, apiKey)) {
                rateLimitPort.recordFailure(rateLimitKey);
                abort(requestContext, 401, "UNAUTHORIZED", "Invalid API key.");
            } else {
                rateLimitPort.recordSuccess(rateLimitKey);
            }
            return;
        }

        // Standard session cookie auth
        if (!authEnabled) return;
        if (ALLOWLISTED_PATHS.contains(path)) return;

        Cookie sessionCookie = requestContext.getCookies().get(AuthController.SESSION_COOKIE);
        if (sessionCookie == null || !sessionManager.validateAndTouch(sessionCookie.getValue())) {
            abort(requestContext, 401, "UNAUTHORIZED", "Authentication required.");
        }
    }

    private String extractClientIp() {
        return AuthController.extractClientIp(vertxRequestProvider.get(), trustForwardedHeaders);
    }

    private void abort(ContainerRequestContext ctx, int status, String code, String message) {
        ctx.abortWith(
                Response.status(status)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", code, "message", message))
                        .build()
        );
    }

    private void abortRateLimited(ContainerRequestContext ctx, long retryAfter) {
        ctx.abortWith(
                Response.status(429)
                        .type(MediaType.APPLICATION_JSON)
                        .header("Retry-After", retryAfter)
                        .entity(Map.of(
                                "code", "TOO_MANY_REQUESTS",
                                "message", "Too many failed attempts. Try again in " + retryAfter + " seconds.",
                                "retryAfter", retryAfter
                        ))
                        .build()
        );
    }
}
