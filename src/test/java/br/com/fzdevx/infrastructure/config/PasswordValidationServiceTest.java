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
        service.rbacSettings = rbacSettings(false, "password");
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

    static RbacSettings rbacSettings(boolean enabled, String mode) {
        RbacSettings settings = new RbacSettings();
        settings.authEnabled = enabled;
        settings.authMode = mode;
        return settings;
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

    // ---- operations password: required=false, password configured ----

    @Test
    void validateOperations_notRequiredButConfigured_correctPassword_returnsTrue() throws Exception {
        assertTrue(opsService("secret", false).validateOperationsPassword("secret"));
    }

    @Test
    void validateOperations_notRequiredButConfigured_wrongPassword_returnsFalse() throws Exception {
        assertFalse(opsService("secret", false).validateOperationsPassword("wrong"));
    }

    @Test
    void validateOperations_notRequiredButConfigured_nullPassword_returnsFalse() throws Exception {
        assertFalse(opsService("secret", false).validateOperationsPassword(null));
    }

    // ---- operations password: required=false, no password configured ----

    @Test
    void validateOperations_notRequiredAndNotConfigured_returnsTrue() throws Exception {
        assertTrue(opsService(null, false).validateOperationsPassword("anything"));
    }

    // ---- operations password: required but no password configured ----

    @Test
    void validateOperations_requiredButNoPasswordConfigured_returnsFalse() throws Exception {
        assertFalse(opsService(null, true).validateOperationsPassword("anything"));
    }

    @Test
    void validateOperations_requiredButBlankPasswordConfigured_returnsFalse() throws Exception {
        assertFalse(opsService("  ", true).validateOperationsPassword("anything"));
    }

    // ---- isOperationsPasswordRequired ----

    @Test
    void isOperationsPasswordRequired_requiredAndConfigured_returnsTrue() throws Exception {
        assertTrue(opsService("secret", true).isOperationsPasswordRequired());
    }

    @Test
    void isOperationsPasswordRequired_requiredButNotConfigured_returnsTrue() throws Exception {
        assertTrue(opsService(null, true).isOperationsPasswordRequired());
    }

    @Test
    void isOperationsPasswordRequired_notRequiredButConfigured_returnsTrue() throws Exception {
        assertTrue(opsService("secret", false).isOperationsPasswordRequired());
    }

    @Test
    void isOperationsPasswordRequired_notRequiredAndNotConfigured_returnsFalse() throws Exception {
        assertFalse(opsService(null, false).isOperationsPasswordRequired());
    }

    // ---- isUploadPasswordRequired ----

    @Test
    void isUploadPasswordRequired_requiredAndConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, "upload", true, null, false, null, false);
        assertTrue(service.isUploadPasswordRequired());
    }

    @Test
    void isUploadPasswordRequired_requiredButNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, true, null, false, null, false);
        assertTrue(service.isUploadPasswordRequired());
    }

    @Test
    void isUploadPasswordRequired_notRequiredButConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, "upload", false, null, false, null, false);
        assertTrue(service.isUploadPasswordRequired());
    }

    @Test
    void isUploadPasswordRequired_notRequiredAndNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertFalse(service.isUploadPasswordRequired());
    }

    // ---- isSchedulingPasswordRequired ----

    @Test
    void isSchedulingPasswordRequired_requiredAndConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, "sched", true, null, false);
        assertTrue(service.isSchedulingPasswordRequired());
    }

    @Test
    void isSchedulingPasswordRequired_requiredButNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, true, null, false);
        assertTrue(service.isSchedulingPasswordRequired());
    }

    @Test
    void isSchedulingPasswordRequired_notRequiredButConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, "sched", false, null, false);
        assertTrue(service.isSchedulingPasswordRequired());
    }

    @Test
    void isSchedulingPasswordRequired_notRequiredAndNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertFalse(service.isSchedulingPasswordRequired());
    }

    // ---- isTerminalPasswordRequired ----

    @Test
    void isTerminalPasswordRequired_requiredAndConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", true);
        assertTrue(service.isTerminalPasswordRequired());
    }

    @Test
    void isTerminalPasswordRequired_requiredButNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, null, true);
        assertTrue(service.isTerminalPasswordRequired());
    }

    @Test
    void isTerminalPasswordRequired_notRequiredButConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", false);
        assertTrue(service.isTerminalPasswordRequired());
    }

    @Test
    void isTerminalPasswordRequired_notRequiredAndNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertFalse(service.isTerminalPasswordRequired());
    }

    // ---- upload password ----

    @Test
    void validateUpload_requiredWithCorrectPassword_returnsTrue() throws Exception {
        var service = createService(null, false, "upload123", true, null, false, null, false);
        assertTrue(service.validateUploadPassword("upload123"));
    }

    @Test
    void validateUpload_requiredWithWrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, "upload123", true, null, false, null, false);
        assertFalse(service.validateUploadPassword("wrong"));
    }

    @Test
    void validateUpload_requiredButNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, true, null, false, null, false);
        assertFalse(service.validateUploadPassword("anything"));
    }

    @Test
    void validateUpload_notRequiredButConfigured_correctPassword_returnsTrue() throws Exception {
        var service = createService(null, false, "upload123", false, null, false, null, false);
        assertTrue(service.validateUploadPassword("upload123"));
    }

    @Test
    void validateUpload_notRequiredButConfigured_wrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, "upload123", false, null, false, null, false);
        assertFalse(service.validateUploadPassword("wrong"));
    }

    @Test
    void validateUpload_notRequiredAndNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertTrue(service.validateUploadPassword(null));
    }

    // ---- scheduling password ----

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
    void validateScheduling_requiredButNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, true, null, false);
        assertFalse(service.validateSchedulingPassword("anything"));
    }

    @Test
    void validateScheduling_notRequiredButConfigured_correctPassword_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, "sched", false, null, false);
        assertTrue(service.validateSchedulingPassword("sched"));
    }

    @Test
    void validateScheduling_notRequiredButConfigured_wrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, "sched", false, null, false);
        assertFalse(service.validateSchedulingPassword("wrong"));
    }

    @Test
    void validateScheduling_notRequiredAndNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertTrue(service.validateSchedulingPassword("anything"));
    }

    // ---- terminal password ----

    @Test
    void validateTerminal_requiredWithCorrectPassword_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", true);
        assertTrue(service.validateTerminalPassword("term"));
    }

    @Test
    void validateTerminal_requiredWithWrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", true);
        assertFalse(service.validateTerminalPassword("wrong"));
    }

    @Test
    void validateTerminal_requiredButNotConfigured_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, null, true);
        assertFalse(service.validateTerminalPassword("anything"));
    }

    @Test
    void validateTerminal_notRequiredButConfigured_correctPassword_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", false);
        assertTrue(service.validateTerminalPassword("term"));
    }

    @Test
    void validateTerminal_notRequiredButConfigured_wrongPassword_returnsFalse() throws Exception {
        var service = createService(null, false, null, false, null, false, "term", false);
        assertFalse(service.validateTerminalPassword("wrong"));
    }

    @Test
    void validateTerminal_notRequiredAndNotConfigured_returnsTrue() throws Exception {
        var service = createService(null, false, null, false, null, false, null, false);
        assertTrue(service.validateTerminalPassword("anything"));
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
    void validateOperations_nullPassword_stillRateLimited() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertFalse(service.validateOperationsPassword(null));
        verify(rateLimitPort).recordFailure("ops-pw:10.0.0.1");
    }

    @Test
    void validateOperations_blankPassword_stillRateLimited() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = opsServiceWithRateLimit("secret", rateLimitPort, mockRequestProvider("10.0.0.1"));

        assertFalse(service.validateOperationsPassword("  "));
        verify(rateLimitPort).recordFailure("ops-pw:10.0.0.1");
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
    void validateOperations_notRequiredButConfigured_rateLimitsAndRejects() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        when(rateLimitPort.checkRateLimit(anyString())).thenReturn(Optional.empty());
        var service = createService("secret", false, null, false, null, false, null, false);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertFalse(service.validateOperationsPassword("wrong"));
        verify(rateLimitPort).recordFailure("ops-pw:10.0.0.1");
    }

    @Test
    void validateOperations_notRequiredAndNotConfigured_skipsRateLimit() throws Exception {
        var rateLimitPort = mock(RateLimitPort.class);
        var service = createService(null, false, null, false, null, false, null, false);
        setField(service, "rateLimitPort", rateLimitPort);
        setField(service, "requestProvider", mockRequestProvider("10.0.0.1"));
        setField(service, "trustForwardedHeaders", false);

        assertTrue(service.validateOperationsPassword("anything"));
        verifyNoInteractions(rateLimitPort);
    }

    // ---- RBAC mode: role permissions replace the password tiers ----

    @Test
    void rbacEnabled_allValidatesPassWithoutPassword() throws Exception {
        var service = createService("secret", true, "secret", true, "secret", true, "secret", true);
        service.rbacSettings = rbacSettings(true, "rbac");

        assertTrue(service.validateOperationsPassword(null));
        assertTrue(service.validateUploadPassword(""));
        assertTrue(service.validateSchedulingPassword("wrong"));
        assertTrue(service.validateTerminalPassword(null));
    }

    @Test
    void rbacEnabled_noPasswordsReportedRequired() throws Exception {
        var service = createService("secret", true, "secret", true, "secret", true, "secret", true);
        service.rbacSettings = rbacSettings(true, "rbac");

        assertFalse(service.isOperationsPasswordRequired());
        assertFalse(service.isUploadPasswordRequired());
        assertFalse(service.isSchedulingPasswordRequired());
        assertFalse(service.isTerminalPasswordRequired());
    }

    @Test
    void rbacModeButAuthDisabled_passwordTiersStillApply() throws Exception {
        var service = createService("secret", true, null, false, null, false, null, false);
        service.rbacSettings = rbacSettings(false, "rbac");

        assertTrue(service.isOperationsPasswordRequired());
        assertFalse(service.validateOperationsPassword("wrong"));
    }
}
