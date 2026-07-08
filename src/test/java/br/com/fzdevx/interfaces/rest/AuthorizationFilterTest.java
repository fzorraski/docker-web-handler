package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.domain.model.auth.Permission;
import br.com.fzdevx.infrastructure.config.CurrentUser;
import br.com.fzdevx.infrastructure.config.RbacSettings;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorizationFilterTest {

    @Mock ContainerRequestContext requestContext;
    @Mock ResourceInfo resourceInfo;
    @Mock UriInfo uriInfo;
    @Mock RbacSettings rbacSettings;

    AuthorizationFilter filter;
    CurrentUser currentUser;

    // ---- fixture resource classes ----

    @RequiresPermission(Permission.DATABASE_VIEW)
    static class AnnotatedResource {
        public void classDefaultMethod() {}

        @RequiresPermission(Permission.DATABASE_OPERATE)
        public void overriddenMethod() {}

        @RequiresPermission({Permission.SCHEDULES_MANAGE, Permission.DATABASE_OPERATE})
        public void anyOfMethod() {}

        @RequiresPermission({})
        public void anyAuthenticatedUserMethod() {}
    }

    static class UnannotatedResource {
        public void openMethod() {}
    }

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthorizationFilter();
        currentUser = new CurrentUser();
        filter.resourceInfo = resourceInfo;
        filter.rbacSettings = rbacSettings;
        setField(filter, "currentUser", currentUser);

        when(rbacSettings.isRbacEnabled()).thenReturn(true);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/database/dumps/list");
        when(requestContext.getMethod()).thenReturn("GET");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void givenResource(Class<?> clazz, String methodName) throws Exception {
        Method method = clazz.getDeclaredMethod(methodName);
        doReturn(clazz).when(resourceInfo).getResourceClass();
        doReturn(method).when(resourceInfo).getResourceMethod();
    }

    // ---- mode / path skips ----

    @Test
    void filter_rbacDisabled_hidesRbacManagementResources() {
        when(rbacSettings.isRbacEnabled()).thenReturn(false);
        doReturn(SettingsController.class).when(resourceInfo).getResourceClass();

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(404, captor.getValue().getStatus());
    }

    @Test
    void filter_rbacDisabled_hidesUserAndRoleControllers() {
        when(rbacSettings.isRbacEnabled()).thenReturn(false);
        for (Class<?> resource : new Class<?>[]{UserController.class, RoleController.class}) {
            clearInvocations(requestContext);
            doReturn(resource).when(resourceInfo).getResourceClass();

            filter.filter(requestContext);

            verify(requestContext).abortWith(any());
        }
    }

    @Test
    void filter_rbacDisabled_allowsEverything() throws Exception {
        when(rbacSettings.isRbacEnabled()).thenReturn(false);
        givenResource(AnnotatedResource.class, "classDefaultMethod");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_authPath_skipsEnforcement() {
        when(uriInfo.getPath()).thenReturn("/auth/me");
        currentUser.set("u1", "alice", Set.of());

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_ciPath_skipsEnforcement() {
        when(uriInfo.getPath()).thenReturn("/ci/deploy");
        currentUser.set("u1", "ci", Set.of());

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    // ---- enforcement ----

    @Test
    void filter_userWithClassLevelPermission_passes() throws Exception {
        givenResource(AnnotatedResource.class, "classDefaultMethod");
        currentUser.set("u1", "alice", Set.of(Permission.DATABASE_VIEW));

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_userWithoutPermission_aborts403WithForbiddenCode() throws Exception {
        givenResource(AnnotatedResource.class, "classDefaultMethod");
        currentUser.set("u1", "alice", Set.of(Permission.LOGS_VIEW));

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(403, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("FORBIDDEN", body.get("code"));
    }

    @Test
    void filter_methodAnnotationOverridesClass() throws Exception {
        givenResource(AnnotatedResource.class, "overriddenMethod");
        // has the class-level permission but not the method-level one
        currentUser.set("u1", "alice", Set.of(Permission.DATABASE_VIEW));

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_anyOfSemantics_passesWithOneOfTwo() throws Exception {
        givenResource(AnnotatedResource.class, "anyOfMethod");
        currentUser.set("u1", "alice", Set.of(Permission.DATABASE_OPERATE));

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_unannotatedResource_allows() throws Exception {
        givenResource(UnannotatedResource.class, "openMethod");
        currentUser.set("u1", "alice", Set.of());

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_emptyAnnotation_allowsAnyAuthenticatedUser() throws Exception {
        givenResource(AnnotatedResource.class, "anyAuthenticatedUserMethod");
        currentUser.set("u1", "alice", Set.of()); // no permissions at all

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_legacyModeCurrentUser_hasAllPermissions() throws Exception {
        // CurrentUser never populated (rbacActive=false) but filter only runs
        // when RBAC is enabled - an unpopulated CurrentUser grants everything,
        // which cannot happen in practice because AuthenticationFilter always
        // populates it first; this documents CurrentUser's fallback behavior.
        assertTrue(new CurrentUser().hasPermission(Permission.SYSTEM_CONFIG));
    }
}
