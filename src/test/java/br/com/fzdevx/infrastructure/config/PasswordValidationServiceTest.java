package br.com.fzdevx.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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
}
