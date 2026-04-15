package br.com.fzdevx.infrastructure.config;

import br.com.fzdevx.application.port.RateLimitPort;
import br.com.fzdevx.domain.exception.RateLimitedException;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordValidationServiceTest {

    private PasswordValidationService createService(
            String operationsPassword, boolean operationsRequired,
            String uploadPassword, boolean uploadRequired,
            String schedulingPassword, boolean schedulingRequired,
            String terminalPassword, boolean terminalRequired
    ) throws Exception {
        PasswordValidationService service = new PasswordValidationService();
        setField(service, "operationsPassword", Optional.ofNullable(operationsPassword));
        setField(service, "operationsPasswordRequired", operationsRequired);
        setField(service, "uploadPassword", Optional.ofNullable(uploadPassword));
        setField(service, "uploadPasswordRequired", uploadRequired);
        setField(service, "schedulingPassword", Optional.ofNullable(schedulingPassword));
        setField(service, "schedulingPasswordRequired", schedulingRequired);
        setField(service, "terminalPassword", Optional.ofNullable(terminalPassword));
        setField(service, "terminalPasswordRequired", terminalRequired);
        return service;
    }

    private PasswordValidationService opsService(String password, boolean required) throws Exception {
        return createService(password, required, null, false, null, false, null, false);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ---- operations password: required=true ----

    @Test
    void validateOperations_requiredWithCorrectPassword_returnsTrue() throws Exception {
        assertTrue(opsService("secret", true).validateOperationsPassword("secret"));
    }

    @Test
    void validateOperations_requiredWithWrongPassword_returnsFalse() throws Exception {
        assertFalse(opsService("secret", true).validateOperationsPassword("wrong"));
    }

    @Test
    void validateOperations_requiredWithNullInput_returnsFalse() throws Exception {
        assertFalse(opsService("secret", true).validateOperationsPassword(null));
    }

    @Test
    void validateOperations_requiredWithEmptyInput_returnsFalse() throws Exception {
        assertFalse(opsService("secret", true).validateOperationsPassword(""));
    }

    // ---- operations password: required=false ----

    @Test
    void validateOperations_notRequired_returnsTrue_withNull() throws Exception {
        assertTrue(opsService("secret", false).validateOperationsPassword(null));
    }

    @Test
    void validateOperations_notRequired_returnsTrue_withWrongPassword() throws Exception {
        assertTrue(opsService("secret", false).validateOperationsPassword("wrong"));
    }

    @Test
    void validateOperations_notRequired_returnsTrue_withEmptyPassword() throws Exception {
        assertTrue(opsService("secret", false).validateOperationsPassword(""));
    }

    // ---- operations password: required but no password configured ----

    @Test
    void validateOperations_requiredButNoPasswordConfigured_returnsTrue() throws Exception {
        assertTrue(opsService(null, true).validateOperationsPassword("anything"));
    }

    @Test
    void validateOperations_requiredButBlankPasswordConfigured_returnsTrue() throws Exception {
        assertTrue(opsService("  ", true).validateOperationsPassword("anything"));
    }

    // ---- isOperationsPasswordRequired ----

    @Test
    void isOperationsPasswordRequired_requiredAndConfigured_returnsTrue() throws Exception {
        assertTrue(opsService("secret", true).isOperationsPasswordRequired());
    }

    @Test
    void isOperationsPasswordRequired_requiredButNotConfigured_returnsFalse() throws Exception {
        assertFalse(opsService(null, true).isOperationsPasswordRequired());
    }

    @Test
    void isOperationsPasswordRequired_notRequired_returnsFalse() throws Exception {
        assertFalse(opsService("secret", false).isOperationsPasswordRequired());
    }

    // ---- upload password (same validate logic) ----

    @Test
    void validateUpload_requiredWithCorrectPassword_returnsTrue() throws Exception {
        var service = createService(null, false, "upload123", true, null, false, null, false);
        assertTrue(service.validateUploadPassword("upload123"));
    }

    @Test
    void validateUpload_notRequired_returnsTrue() throws Exception {
        var service = createService(null, false, "upload123", false, null, false, null, false);
        assertTrue(service.validateUploadPassword(null));
    }

    // ---- scheduling password (custom logic) ----

    @Test
    void validateScheduling_requiredWithCorrectPassword_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, "sched", true, null, false);
        assertTrue(service.validateSchedulingPassword("sched"));
    }

    @Test
    void validateScheduling_requiredWithWrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, "sched", true, null, false);
        assertFalse(service.validateSchedulingPassword("wrong"));
    }

    @Test
    void validateScheduling_notRequired_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, "sched", false, null, false);
        assertTrue(service.validateSchedulingPassword(null));
    }

    @Test
    void validateScheduling_requiredButNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, true, null, false);
        assertFalse(service.validateSchedulingPassword("anything"));
    }

    // ---- terminal password ----

    @Test
    void validateTerminal_requiredWithCorrectPassword_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", true);
        assertTrue(service.validateTerminalPassword("term"));
    }

    @Test
    void validateTerminal_notRequired_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", false);
        assertTrue(service.validateTerminalPassword(null));
    }

    // ---- constantTimeEquals ----

    @Test
    void constantTimeEquals_emptyConfigured_returnsFalse() {
        assertFalse(PasswordValidationService.constantTimeEquals(Optional.empty(), "test"));
    }

    @Test
    void constantTimeEquals_blankConfigured_returnsFalse() {
        assertFalse(PasswordValidationService.constantTimeEquals(Optional.of("  "), "test"));
    }

    @Test
    void constantTimeEquals_nullInput_returnsFalse() {
        assertFalse(PasswordValidationService.constantTimeEquals(Optional.of("secret"), null));
    }

    @Test
    void constantTimeEquals_matching_returnsTrue() {
        assertTrue(PasswordValidationService.constantTimeEquals(Optional.of("secret"), "secret"));
    }

    // ---- rate limiting ----

    private PasswordValidationService opsServiceWithRateLimit(String password, RateLimitPort rateLimitPort,
                                                              Provider<HttpServerRequest> requestProvider) throws Exception {
        var service = opsService(password, true);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", requestProvider);
        setField(service, "trustForwardedHeaders", false);
        return service;
    }

    @SuppressWarnings("unchecked")
    private Provider<HttpServerRequest> mockRequestProvider(String clientIp) {
        SocketAddress addr = mock(SocketAddress.class);
        when(addr.host()).thenReturn(clientIp);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.remoteAddress()).thenReturn(addr);
        Provider<HttpServerRequest> provider = mock(Provider.class);
        when(provider.get()).thenReturn(request);
        return provider;
    }

    @Test
    void validateOperations_blocked_throwsRateLimitedException() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit("ops-pw:10.0.0.1")).thenReturn(Optional.of(30L));
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        var ex = assertThrows(RateLimitedException.class, () -> service.validateOperationsPassword("wrong"));
        assertEquals(30, ex.getRetryAfterSeconds());
    }

    @Test
    void validateOperations_wrongPassword_recordsFailure() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertFalse(service.validateOperationsPassword("wrong"));
        verify(rateLimitPort).recordFailure("ops-pw:10.0.0.1");
    }

    @Test
    void validateOperations_correctPassword_recordsSuccess() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertTrue(service.validateOperationsPassword("secret"));
        verify(rateLimitPort).recordSuccess("ops-pw:10.0.0.1");
    }

    @Test
    void validateOperations_nullPassword_skipsRateLimit() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertFalse(service.validateOperationsPassword(null));
        verifyNoInteractions(rateLimitPort);
    }

    @Test
    void validateOperations_blankPassword_skipsRateLimit() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertFalse(service.validateOperationsPassword("  "));
        verifyNoInteractions(rateLimitPort);
    }

    @SuppressWarnings("unchecked")
    @Test
    void validateOperations_noHttpContext_skipsRateLimit() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        Provider<HttpServerRequest> provider = mock(Provider.class);
        when(provider.get()).thenThrow(new RuntimeException("no context"));
        var service = opsServiceWithRateLimit("secret", rateLimitPort, provider);

        assertFalse(service.validateOperationsPassword("wrong"));
        verifyNoInteractions(rateLimitPort);
    }

    @Test
    void validateTerminal_wrongPassword_recordsFailureWithTerminalCategory() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = createService(null, false, null, false, null, false, "term", true);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertFalse(service.validateTerminalPassword("wrong"));
        verify(rateLimitPort).recordFailure("terminal-pw:10.0.0.1");
    }

    @Test
    void validateScheduling_wrongPassword_recordsFailureWithScheduleCategory() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = createService(null, false, null, false, "sched", true, null, false);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertFalse(service.validateSchedulingPassword("wrong"));
        verify(rateLimitPort).recordFailure("schedule-pw:10.0.0.1");
    }

    @Test
    void validateUpload_wrongPassword_recordsFailureWithUploadCategory() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = createService(null, false, "upload123", true, null, false, null, false);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertFalse(service.validateUploadPassword("wrong"));
        verify(rateLimitPort).recordFailure("upload-pw:10.0.0.1");
    }

    @Test
    void validateOperations_notRequired_skipsRateLimit() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        var service = createService("secret", false, null, false, null, false, null, false);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertTrue(service.validateOperationsPassword("wrong"));
        verifyNoInteractions(rateLimitPort);
    }
}
