package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.RbacSettings;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * Enforces {@link RequiresPermission} annotations when RBAC is active.
 * Runs post-matching, after {@link AuthenticationFilter} has populated
 * {@link CurrentUser}. Method-level annotations override class-level ones.
 * The CI API ({@code /ci/*}) keeps its own API-key auth and the auth
 * endpoints stay open, mirroring the authentication filter's allowlist.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AuthorizationFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    @Inject
    RbacSettings rbacSettings;

    @Inject
    CurrentUser currentUser;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!rbacSettings.isRbacEnabled()) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("/auth") || path.startsWith("/ci/")) {
            return;
        }

        RequiresPermission annotation = resolveAnnotation();
        if (annotation == null) {
            return;
        }

        // empty = any authenticated user (the authentication filter already ran)
        if (annotation.value().length == 0) {
            return;
        }

        boolean allowed = Arrays.stream(annotation.value()).anyMatch(currentUser::hasPermission);
        if (!allowed) {
            String required = Arrays.stream(annotation.value()).map(Permission::name)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            Log.warnf("Access denied: user '%s' lacks permission [%s] for %s %s",
                    currentUser.getUsername(), required,
                    requestContext.getMethod(), path);
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "FORBIDDEN",
                            "message", "You do not have permission to perform this action."))
                    .build());
        }
    }

    private RequiresPermission resolveAnnotation() {
        Method method = resourceInfo.getResourceMethod();
        if (method != null && method.isAnnotationPresent(RequiresPermission.class)) {
            return method.getAnnotation(RequiresPermission.class);
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        return resourceClass != null ? resourceClass.getAnnotation(RequiresPermission.class) : null;
    }
}
