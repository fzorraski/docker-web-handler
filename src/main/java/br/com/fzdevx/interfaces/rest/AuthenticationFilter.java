package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
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
    AuthSessionManager sessionManager;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!authEnabled) return;

        String path = requestContext.getUriInfo().getPath();
        if (ALLOWLISTED_PATHS.contains(path)) return;

        Cookie sessionCookie = requestContext.getCookies().get(AuthController.SESSION_COOKIE);
        if (sessionCookie == null || !sessionManager.validateAndTouch(sessionCookie.getValue())) {
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("code", "UNAUTHORIZED", "message", "Authentication required."))
                            .build()
            );
        }
    }
}
