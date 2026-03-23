package br.com.fzdevx.interfaces.rest;

import br.com.fzdevx.infrastructure.config.AuthSessionManager;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

    @Mock AuthSessionManager sessionManager;
    @Mock ContainerRequestContext requestContext;
    @Mock UriInfo uriInfo;

    @InjectMocks
    AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        setField("authEnabled", true);
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
    }

    private void setField(String name, Object value) {
        try {
            Field f = AuthenticationFilter.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(filter, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- auth disabled ----

    @Test
    void filter_authDisabled_passesThrough() {
        setField("authEnabled", false);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    // ---- allowlisted paths ----

    @Test
    void filter_loginPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/login");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_logoutPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/logout");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_checkPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/check");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void filter_statusPath_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/auth/status");
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    // ---- no cookie ----

    @Test
    void filter_noCookie_aborts401() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        assertEquals(401, captor.getValue().getStatus());
    }

    // ---- invalid session ----

    @Test
    void filter_invalidSession_aborts401() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "bad")));
        when(sessionManager.validateAndTouch("bad")).thenReturn(false);

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- valid session ----

    @Test
    void filter_validSession_passesThrough() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Map.of("DWH-SESSION", new Cookie("DWH-SESSION", "valid")));
        when(sessionManager.validateAndTouch("valid")).thenReturn(true);

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
    }

    // ---- non-allowlisted paths require auth ----

    @Test
    void filter_containersPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_imagesPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/images/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_schedulesPath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/schedules/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    @Test
    void filter_databasePath_requiresAuth() {
        when(uriInfo.getPath()).thenReturn("/database/dumps/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        verify(requestContext).abortWith(any());
    }

    // ---- response body format ----

    @Test
    @SuppressWarnings("unchecked")
    void filter_401Response_hasCorrectBody() {
        when(uriInfo.getPath()).thenReturn("/containers/list");
        when(requestContext.getCookies()).thenReturn(Collections.emptyMap());

        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Response response = captor.getValue();
        Map<String, String> body = (Map<String, String>) response.getEntity();
        assertEquals("UNAUTHORIZED", body.get("code"));
        assertEquals("Authentication required.", body.get("message"));
    }
}
