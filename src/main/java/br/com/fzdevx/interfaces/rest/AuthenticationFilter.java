package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import br.com.fzdevx.infrastructure.config.PasswordValidationService;
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
    AuthSessionManager sessionManager;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();

        // CI API key auth (independent of app.auth.enabled)
        if (path.startsWith("/ci/")) {
            if (!ciEnabled) {
                abort(requestContext, 404, "NOT_FOUND", "CI API is not enabled.");
                return;
            }
            String apiKey = requestContext.getHeaderString("X-API-Key");
            if (!PasswordValidationService.constantTimeEquals(ciApiKey, apiKey)) {
                abort(requestContext, 401, "UNAUTHORIZED", "Invalid API key.");
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

    private void abort(ContainerRequestContext ctx, int status, String code, String message) {
        ctx.abortWith(
                Response.status(status)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", code, "message", message))
                        .build()
        );
    }
}
